import { test, expect } from "../fixtures";
import { uniqueName, TestApiClient, type Task } from "../helpers/api-client";

// The Autopilot is a SINGLETON with no per-org/per-worker scope filter:
// its candidate source is every Epic on the board, and `POST /tick` is the only thing that
// advances it in this stack — `AUTOPILOT_ENABLED=false` on the api-server in
// docker-compose.e2e.yaml keeps AutopilotReconciler's 30s scheduler off, precisely so that
// engaging the singleton from a Playwright worker cannot flip Tasks created by OTHER parallel
// workers out of `backlog` on a timer nobody in this suite controls.
//
// That does not make `POST /tick` itself free of the same hazard: any call this file makes
// starts every READY backlog Task, and designs every undesigned Story, on the WHOLE board, up to
// `maxParallel` slots, not just this worker's own. In particular, a board/list spec (e.g.
// task-board.spec.ts's "drag a task card to a legal target column" test) creates a plain,
// unblocked Task and asserts it sits in the Backlog column before acting on it — if a tick from
// this file lands in that window, it can silently start that Task first, and the resulting
// failure looks exactly like infrastructure flakiness in an unrelated file. There is no way to
// close this from inside one spec file alone (there is no scope to filter by), so the
// mitigations here are damage limitation, not elimination:
//   - `test.describe.configure({ mode: "serial" })` keeps this file's own tests on one worker
//     — Playwright's `fullyParallel: true` would otherwise schedule them onto different workers,
//     which would race each other over the shared `maxParallel`/`engaged` state (this is a
//     different problem from the scheduler one above: it's about THIS file racing itself, which
//     `mode: "serial"` does fix, unlike the 30s scheduler that isn't a worker at all).
//   - Every `tick()` call below asks for the smallest amount of headroom that makes the assertion
//     that follows it deterministic, never an unbounded ceiling, to bound how many stray Tasks a
//     single tick could sweep up.
//   - `afterAll` disengages, and every assertion below is scoped to this test's own
//     `uniqueName()`-tagged resources — never a board-wide count.
// Slot counts (`inFlight`, `awaitingHuman`) are deployment-scoped: a multi-tenant overlay counts
// every run in the tenant, so other workers' runs move them between a read and the tick that
// follows it. No assertion here may rest on such a count holding still.
// If this proves to cause real cross-file flakiness in CI, the follow-up options are giving this
// file its own isolated CI lane (no other spec running concurrently) or a scoped candidate
// source — both bigger changes than this task's brief covers.
test.describe.configure({ mode: "serial" });

// A tick works the board in priority order, and other specs leave medium-priority Stories
// undesigned: at any lower priority, a tick designs those before it reaches this file's Tasks.
const AHEAD_OF_THE_BOARD = "high";

test.describe("Autopilot", () => {
  // A run left going parks at Final Approval under this Autopilot, filling a later test's
  // max_awaiting_human ceiling mid-measurement. A cancel settles it without touching the breaker.
  const storiesWithRuns: string[] = [];
  test.afterEach(async ({ api }) => {
    for (const storyId of storiesWithRuns.splice(0)) {
      for (const task of await api.listTasks(storyId)) {
        if (task.latestRunId) await api.cancelRun(task.latestRunId).catch(() => {});
      }
    }
  });

  test.afterAll(async () => {
    // Never touches in-flight runs (AutopilotService#disengage) — just leaves the org in the
    // resting "off" state so nothing outside this file's own tick() calls can start anything.
    await new TestApiClient().disengageAutopilot();
  });

  test("tick starts only the READY Task in the frontier, leaving a blocked Task in backlog", async ({
    api,
    cloneableProject,
    autopilotPage,
  }) => {
    test.setTimeout(300_000);
    const epic = await api.createEpic({
      title: uniqueName("Autopilot Frontier Epic"),
      description: "desc",
      softwareProjectId: cloneableProject.id,
      priority: AHEAD_OF_THE_BOARD,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot Frontier Story"),
      description: "desc",
    });
    storiesWithRuns.push(story.id);
    const readyTask = await api.createTask(story.id, {
      title: uniqueName("Autopilot Ready Task"),
      description: "desc",
    });
    const blockedTask = await api.createTask(story.id, {
      title: uniqueName("Autopilot Blocked Task"),
      description: "desc",
    });

    // readyTask blocks blockedTask. readyTask never reaches "done" in this test (starting it
    // only gets it to in_progress), so blockedTask stays BLOCKED for the whole test; readyTask
    // itself has no incoming edge, so it's READY from creation.
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: readyTask.id,
      blockedItemType: "task",
      blockedItemId: blockedTask.id,
    });
    await api.approveStorySpec(story.id);

    const before = await api.getAutopilot();
    // Headroom over CURRENT inFlight, not an unlimited ceiling: enough free slots that our one
    // READY Task is started even under a few concurrently-in-flight Autopilot runs, while
    // bounding how many stray board Tasks a single tick could start if this lands badly (see the
    // file-level comment above).
    await api.updateAutopilot({ maxParallel: before.inFlight + 10 });
    await api.engageAutopilot();
    await api.tickAutopilot();

    const tasks = await api.listTasks(story.id);
    const readyAfter = tasks.find((t) => t.id === readyTask.id);
    const blockedAfter = tasks.find((t) => t.id === blockedTask.id);

    expect(readyAfter?.status).toBe("in_progress");
    expect(readyAfter?.latestRunId).not.toBeNull();
    expect(readyAfter?.readiness).toBe("READY");
    expect(blockedAfter?.status).toBe("backlog");
    expect(blockedAfter?.readiness).toBe("BLOCKED");

    // Light UI corroboration: the page reflects the state the API calls above just produced.
    // Not the primary assertion — the Task-status checks above are, since the page's own fetch
    // could in principle race a STOMP update from a concurrent worker's tick — just confirmation
    // that GET /autopilot and the page agree on `engaged` and `inFlight`.
    await autopilotPage.goto();
    await expect(autopilotPage.toggle).toHaveAttribute("aria-label", "Disengage Autopilot");
    await expect(autopilotPage.inFlight).toBeVisible();

    // The Epic stays: readyTask is now in_progress, and DefaultEpicService#delete refuses to
    // delete an Epic with any started descendant Task — the same accepted convention
    // task-board.spec.ts's board-drag test documents. uniqueName() keeps the leftover row from
    // colliding with anything.
  });

  test("a paused run is listed as awaiting you, and a tick with a free slot still starts its Story's next Task", async ({
    api,
    cloneableProject,
  }) => {
    test.setTimeout(300_000);
    const epic = await api.createEpic({
      title: uniqueName("Autopilot Slot Epic"),
      description: "desc",
      softwareProjectId: cloneableProject.id,
      priority: AHEAD_OF_THE_BOARD,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot Slot Story"),
      description: "desc",
    });
    storiesWithRuns.push(story.id);
    const taskA = await api.createTask(story.id, {
      title: uniqueName("Autopilot Slot Task A"),
      description: "desc",
    });
    await api.approveStorySpec(story.id);
    await api.engageAutopilot();

    // `inFlight + 1` is one free slot only while the count holds still (see the top of this file);
    // a tick handed more starts every READY Task it reaches, so taskB does not exist yet. The slot
    // can also go to another worker's Task, so tick until taskA claims it.
    let started: Task | undefined;
    for (let attempt = 0; attempt < 10 && !started; attempt++) {
      const status = await api.getAutopilot();
      await api.updateAutopilot({ maxParallel: status.inFlight + 1 });
      await api.tickAutopilot();
      started = (await api.listTasks(story.id)).find(
        (t) => t.id === taskA.id && t.status === "in_progress",
      );
    }
    if (!started) {
      throw new Error(
        "Autopilot never started Task A after 10 ticks — see the cross-worker slot " +
          "contention note in this file's top-of-file comment.",
      );
    }
    expect(started.latestRunId).toBeTruthy();
    const pausedRunId = started.latestRunId!;

    await api.pauseRun(pausedRunId);
    await api.waitForRunStatus(pausedRunId, ["paused"], 30_000);

    // `awaitingYou` lists only this Autopilot's own runs, so it is exact under any slot-count scope;
    // a tenant-wide `inFlight` cannot isolate the slot the pause frees. That half is pinned by
    // AutopilotServiceIntegrationTest#tick_parkedRunFreesItsSlot_soTheNextTaskStarts.
    const parked = await api.getAutopilot();
    expect(parked.awaitingYou.find((ref) => ref.runId === pausedRunId)?.status).toBe("paused");

    // Created only now, like gatedTask below: READY any earlier, a tick handed a second slot would
    // start it alongside taskA.
    const taskB = await api.createTask(story.id, {
      title: uniqueName("Autopilot Slot Task B"),
      description: "desc",
    });

    // Same bounded-retry reasoning as the first loop.
    let taskBStarted = false;
    for (let attempt = 0; attempt < 10 && !taskBStarted; attempt++) {
      const status = await api.getAutopilot();
      await api.updateAutopilot({ maxParallel: status.inFlight + 1 });
      await api.tickAutopilot();
      taskBStarted =
        (await api.listTasks(story.id)).find((t) => t.id === taskB.id)?.status === "in_progress";
    }
    expect(taskBStarted).toBe(true);

    // The Epic stays — same started-descendant-Task convention as the previous test.
  });

  test("max_awaiting_human throttles new starts while parked runs fill it, and starts resume below it", async ({
    api,
    cloneableProject,
  }) => {
    test.setTimeout(300_000);
    // max_parallel never withholds this test's Tasks: this Epic's work sorts first, and every tick
    // below offers it a free slot, so a lack of a start can only be attributed to the
    // max_awaiting_human ceiling under test.
    const epic = await api.createEpic({
      title: uniqueName("Autopilot Awaiting Human Epic"),
      description: "desc",
      softwareProjectId: cloneableProject.id,
      priority: AHEAD_OF_THE_BOARD,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot Awaiting Human Story"),
      description: "desc",
    });
    storiesWithRuns.push(story.id);
    const parkedTask = await api.createTask(story.id, {
      title: uniqueName("Autopilot Awaiting Human Parked Task"),
      description: "desc",
    });

    await api.approveStorySpec(story.id);
    const originalMaxAwaitingHuman = (await api.getAutopilot()).maxAwaitingHuman ?? 0;
    await api.engageAutopilot();

    try {
      // Started by THIS Autopilot, not by hand: SingleTenantAutopilotSlotCounter counts only this
      // Autopilot's own runs, so only such a run fills the ceiling below in every deployment.
      // One slot per tick: wider headroom also starts other specs' work.
      let started: Task | undefined;
      for (let attempt = 0; attempt < 10 && !started; attempt++) {
        const status = await api.getAutopilot();
        await api.updateAutopilot({ maxParallel: status.inFlight + 1 });
        await api.tickAutopilot();
        started = (await api.listTasks(story.id)).find(
          (t) => t.id === parkedTask.id && t.status === "in_progress",
        );
      }
      if (!started) {
        throw new Error(
          "Autopilot never started the parked Task after 10 ticks — see the cross-worker slot " +
            "contention note in this file's top-of-file comment.",
        );
      }
      const parkedRunId = started.latestRunId!;
      await api.pauseRun(parkedRunId);
      await api.waitForRunStatus(parkedRunId, ["paused"], 30_000);

      // A ceiling of 1, which parkedTask's run fills by itself: a ceiling equal to the current count
      // lifts as soon as any other parked run leaves it. Set before gatedTask exists, so no pass
      // ever sees gatedTask READY under a looser ceiling.
      const parked = await api.getAutopilot();
      expect(parked.awaitingYou.some((ref) => ref.runId === parkedRunId)).toBe(true);
      expect(parked.awaitingHuman ?? 0).toBeGreaterThanOrEqual(1);
      await api.updateAutopilot({ maxParallel: parked.inFlight + 10, maxAwaitingHuman: 1 });
      const gatedTask = await api.createTask(story.id, {
        title: uniqueName("Autopilot Awaiting Human Gated Task"),
        description: "desc",
      });
      await api.tickAutopilot();

      const afterThrottledTick = await api.listTasks(story.id);
      expect(afterThrottledTick.find((t) => t.id === gatedTask.id)?.status).toBe("backlog");

      // Drain the backlog. There is no "resume" endpoint on this API — cancelling is what removes
      // a run from the awaiting-human bucket entirely (AutopilotService#classify has no bucket for
      // `cancelled`).
      await api.cancelRun(parkedRunId);
      await api.waitForRunStatus(parkedRunId, ["cancelled"], 30_000);
      expect((await api.getAutopilot()).awaitingYou.some((ref) => ref.runId === parkedRunId)).toBe(false);

      // Other runs may still fill a tenant-wide count after the drain, so each tick sets the ceiling
      // one above the current count.
      let gatedStarted = false;
      for (let attempt = 0; attempt < 10 && !gatedStarted; attempt++) {
        const status = await api.getAutopilot();
        await api.updateAutopilot({
          maxParallel: status.inFlight + 1,
          maxAwaitingHuman: (status.awaitingHuman ?? 0) + 1,
        });
        await api.tickAutopilot();
        gatedStarted =
          (await api.listTasks(story.id)).find((t) => t.id === gatedTask.id)?.status === "in_progress";
      }
      expect(gatedStarted).toBe(true);
    } finally {
      // Never leaves a ceiling behind that would poison a sibling serial test in this file.
      await api.updateAutopilot({ maxAwaitingHuman: originalMaxAwaitingHuman });
    }

    // The Epic stays — both Tasks are now started (one via a cancelled run), and
    // DefaultEpicService#delete refuses to delete an Epic with a started descendant Task, same
    // convention as the neighboring tests in this file.
  });

  test("a Task stranded by a cancelled run is reported as held, and its run is badged", async ({
    api,
    cloneableProject,
    autopilotPage,
    runMonitorPage,
  }) => {
    test.setTimeout(300_000);
    const epic = await api.createEpic({
      title: uniqueName("Autopilot Held Epic"),
      description: "desc",
      softwareProjectId: cloneableProject.id,
      priority: AHEAD_OF_THE_BOARD,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot Held Story"),
      description: "desc",
    });
    storiesWithRuns.push(story.id);
    const heldTitle = uniqueName("Autopilot Held Task");
    const task = await api.createTask(story.id, { title: heldTitle, description: "desc" });
    await api.approveStorySpec(story.id);

    await api.engageAutopilot();

    // ONE free slot at a time, never a ceiling. A tick starts READY backlog Tasks from the WHOLE
    // board, so surplus headroom here sweeps up Tasks other specs are mid-assertion on and breaks
    // their teardown with a 409 on Epic delete — the exact hazard described at the top of this
    // file. Same bounded-retry shape as the slot test above: whichever tick lands our Task wins,
    // and the loop absorbs a slot that went to another worker first.
    let started: Task | undefined;
    for (let attempt = 0; attempt < 10 && !started; attempt++) {
      const status = await api.getAutopilot();
      await api.updateAutopilot({ maxParallel: status.inFlight + 1 });
      await api.tickAutopilot();
      started = (await api.listTasks(story.id)).find(
        (t) => t.id === task.id && t.status === "in_progress",
      );
    }
    if (!started) {
      throw new Error(
        "Autopilot never started the Task after 10 ticks — see the cross-worker slot " +
          "contention note in this file's top-of-file comment.",
      );
    }

    // Zero headroom from here on: nothing below this line needs a start, and the 30s reconciler
    // is off in this stack, so no further Task can leave backlog on this test's account.
    const afterStart = await api.getAutopilot();
    await api.updateAutopilot({ maxParallel: Math.max(1, afterStart.inFlight) });

    const runId = started.latestRunId!;
    expect(runId).toBeTruthy();

    // The attribution half: this run has an autopilot_id, so the header badges it. A run a
    // person started carries no badge — covered by RunHeader's unit test, since producing a
    // manually started run here would mean a second Task and a second slot.
    await runMonitorPage.goto(runId);
    await expect(runMonitorPage.autopilotBadge).toBeVisible();

    // Cancelling is the operator's recovery gesture, and it deliberately leaves the Task where
    // it is. That is what strands it: the ready frontier only sweeps `backlog`.
    await api.cancelRun(runId);
    await api.waitForRunStatus(runId, ["cancelled"], 30_000);

    const afterCancel = (await api.listTasks(story.id)).find((t) => t.id === task.id);
    expect(afterCancel?.status).toBe("in_progress");

    // No tick needed: GET /autopilot runs the same frontier sweep (AutopilotService#snapshot),
    // so the held list is computed on read. Ticking here would only widen this test's reach over
    // the shared board for no assertion.
    await autopilotPage.goto();
    await expect(autopilotPage.heldTasks).toContainText(heldTitle);
    // Links to the Task, not to the cancelled run: that run is over, and Restart lives on the Task.
    await expect(autopilotPage.heldTasks.getByRole("link", { name: heldTitle })).toHaveAttribute(
      "href",
      `/tasks/${task.id}`,
    );
    // Asserted on the phrase rather than on this Task's title: the why-idle line names only the
    // first few held Tasks, and a previous run of this spec may have left its own behind.
    await expect(autopilotPage.whyIdle).toContainText("left in progress by a finished run");

    // Best-effort: clears the hold so repeated runs of this spec do not accumulate held Tasks on
    // the shared board. Tolerated failure — the assertions above are already done, and the Epic
    // itself cannot be deleted either way (started descendant Task).
    await api.completeTask(task.id).catch(() => {});
  });

  test("POST /tasks/{id}/start returns 409 for a blocked Task", async ({ api, workerRepo }) => {
    const epic = await api.createEpic({
      title: uniqueName("Autopilot 409 Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot 409 Story"),
      description: "desc",
    });
    const blockingTask = await api.createTask(story.id, {
      title: uniqueName("Autopilot 409 Blocking Task"),
      description: "desc",
    });
    const blockedTask = await api.createTask(story.id, {
      title: uniqueName("Autopilot 409 Blocked Task"),
      description: "desc",
    });

    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: blockingTask.id,
      blockedItemType: "task",
      blockedItemId: blockedTask.id,
    });

    try {
      // This is TaskService#start's own readiness gate (ReadinessAuthMode.PUBLIC) — it never
      // touches the Autopilot singleton, so this test needs no engage/tick at all.
      // Matched on the message: a Task under an undesigned Story is refused with a 409 too, and
      // the blocked check answers first.
      await expect(api.startTask(blockedTask.id)).rejects.toThrow(/→ 409:.*Cannot start a blocked Task/);

      const tasks = await api.listTasks(story.id);
      const stillBlocked = tasks.find((t) => t.id === blockedTask.id);
      expect(stillBlocked?.status).toBe("backlog");
    } finally {
      // Safe here (unlike the two tests above): the 409 means nothing ever started, so both
      // Tasks are still in backlog and DefaultEpicService#delete allows the delete.
      await api.deleteEpic(epic.id);
    }
  });
});
