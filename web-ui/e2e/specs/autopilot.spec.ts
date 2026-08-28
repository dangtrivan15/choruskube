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
// starts every READY backlog Task on the WHOLE board, up to `maxParallel` slots, not just this
// worker's own. In particular, a board/list spec (e.g. task-board.spec.ts's "drag a task card
// to a legal target column" test) creates a plain, unblocked Task and asserts it sits in the
// Backlog column before acting on it — if a tick from this file lands in that window, it can
// silently start that Task first, and the resulting failure looks exactly like infrastructure
// flakiness in an unrelated file. There is no way to close this from inside one spec file alone
// (there is no scope to filter by), so the mitigations here are damage limitation, not
// elimination:
//   - `test.describe.configure({ mode: "serial" })` keeps this file's own 3 tests on one worker
//     — Playwright's `fullyParallel: true` would otherwise schedule them onto different workers,
//     which would race each other over the shared `maxParallel`/`engaged` state (this is a
//     different problem from the scheduler one above: it's about THIS file racing itself, which
//     `mode: "serial"` does fix, unlike the 30s scheduler that isn't a worker at all).
//   - Every `tick()` call below asks for the smallest amount of headroom that makes the assertion
//     that follows it deterministic, never an unbounded ceiling, to bound how many stray Tasks a
//     single tick could sweep up.
//   - `afterAll` disengages, and every assertion below is scoped to this test's own
//     `uniqueName()`-tagged resources — never a board-wide count.
// If this proves to cause real cross-file flakiness in CI, the follow-up options are giving this
// file its own isolated CI lane (no other spec running concurrently) or a scoped candidate
// source — both bigger changes than this task's brief covers.
test.describe.configure({ mode: "serial" });

test.describe("Autopilot", () => {
  test.afterAll(async () => {
    // Never touches in-flight runs (AutopilotService#disengage) — just leaves the org in the
    // resting "off" state so nothing outside this file's own tick() calls can start anything.
    await new TestApiClient().disengageAutopilot();
  });

  test("tick starts only the READY Task in the frontier, leaving a blocked Task in backlog", async ({
    api,
    workerRepo,
    autopilotPage,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("Autopilot Frontier Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot Frontier Story"),
      description: "desc",
    });
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

    const before = await api.getAutopilot();
    // Headroom over CURRENT inFlight, not an unlimited ceiling: enough free slots that our one
    // READY Task is started even under a few concurrently-in-flight Autopilot runs, while
    // bounding how many stray board Tasks a single tick could start if this lands badly (see the
    // file-level comment above).
    await api.updateAutopilot(before.inFlight + 10);
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

    // No cleanup: readyTask is now in_progress, and DefaultEpicService#delete refuses to delete
    // an Epic with any started descendant Task — the same accepted convention
    // task-board.spec.ts's board-drag test documents. uniqueName() keeps the leftover row from
    // colliding with anything.
  });

  test("a paused run frees its slot for the next tick", async ({ api, workerRepo }) => {
    const epic = await api.createEpic({
      title: uniqueName("Autopilot Slot Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot Slot Story"),
      description: "desc",
    });
    const taskA = await api.createTask(story.id, {
      title: uniqueName("Autopilot Slot Task A"),
      description: "desc",
    });
    const taskB = await api.createTask(story.id, {
      title: uniqueName("Autopilot Slot Task B"),
      description: "desc",
    });
    // taskA and taskB are independent — no dependency edge between them — so both are READY
    // from creation. Which one the Autopilot picks first is intentionally left unconstrained
    // (the tie-break is `created_at` then Task id, and this test doesn't need to know
    // the answer): whichever starts is "started", the other is "pending", and the causal claim
    // this test proves — pausing started's run lets pending start — holds either way.
    await api.engageAutopilot();

    // Offer exactly ONE free slot at a time so a tick can start at most one Task globally, then
    // tick until one of ours claims it. The Autopilot's candidate source spans the whole board,
    // so in principle that one slot could go to another worker's Task instead of
    // ours — this file's tests are the only Autopilot-attributed traffic in the suite (serialised
    // onto one worker, see the top-of-file comment), so in practice this converges on the first
    // or second attempt; the bounded retry absorbs the rest.
    let started: Task | undefined;
    let pending: Task | undefined;
    for (let attempt = 0; attempt < 10 && !started; attempt++) {
      const status = await api.getAutopilot();
      await api.updateAutopilot(status.inFlight + 1);
      await api.tickAutopilot();
      const tasks = await api.listTasks(story.id);
      const a = tasks.find((t) => t.id === taskA.id);
      const b = tasks.find((t) => t.id === taskB.id);
      if (a?.status === "in_progress") {
        started = a;
        pending = b;
      } else if (b?.status === "in_progress") {
        started = b;
        pending = a;
      }
    }
    if (!started || !pending) {
      throw new Error(
        "Autopilot never started either Task after 10 ticks — see the cross-worker slot " +
          "contention note in this file's top-of-file comment.",
      );
    }
    expect(pending.status).toBe("backlog");
    expect(started.latestRunId).toBeTruthy();

    // Clamp maxParallel to exactly current usage — zero headroom — so `pending` cannot start
    // for any reason OTHER than the slot `started`'s run is about to free.
    const afterStart = await api.getAutopilot();
    await api.updateAutopilot(Math.max(1, afterStart.inFlight));

    // Pausing occupies no slot (AutopilotService#classify groups `paused` with
    // `awaiting_human`/`live_chat` — "costs nothing to hold"), without touching the Task's own
    // `in_progress` status.
    await api.pauseRun(started.latestRunId!);
    await api.waitForRunStatus(started.latestRunId!, ["paused"], 30_000);

    // Re-confirm pending is still backlog with the slot pinned at zero headroom, then offer
    // exactly one free slot again — freed by the pause above — and tick until pending claims
    // it. Same bounded-retry reasoning as the first loop.
    let pendingStarted = false;
    for (let attempt = 0; attempt < 10 && !pendingStarted; attempt++) {
      const status = await api.getAutopilot();
      await api.updateAutopilot(status.inFlight + 1);
      await api.tickAutopilot();
      const tasks = await api.listTasks(story.id);
      const p = tasks.find((t) => t.id === pending!.id);
      if (p?.status === "in_progress") {
        pendingStarted = true;
      }
    }
    expect(pendingStarted).toBe(true);

    // No cleanup — same started-descendant-Task convention as the previous test.
  });

  test("a Task stranded by a cancelled run is reported as held, and its run is badged", async ({
    api,
    workerRepo,
    autopilotPage,
    runMonitorPage,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("Autopilot Held Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Autopilot Held Story"),
      description: "desc",
    });
    const heldTitle = uniqueName("Autopilot Held Task");
    const task = await api.createTask(story.id, { title: heldTitle, description: "desc" });

    await api.engageAutopilot();

    // ONE free slot at a time, never a ceiling. A tick starts READY backlog Tasks from the WHOLE
    // board, so surplus headroom here sweeps up Tasks other specs are mid-assertion on and breaks
    // their teardown with a 409 on Epic delete — the exact hazard described at the top of this
    // file. Same bounded-retry shape as the slot test above: whichever tick lands our Task wins,
    // and the loop absorbs a slot that went to another worker first.
    let started: Task | undefined;
    for (let attempt = 0; attempt < 10 && !started; attempt++) {
      const status = await api.getAutopilot();
      await api.updateAutopilot(status.inFlight + 1);
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
    await api.updateAutopilot(Math.max(1, afterStart.inFlight));

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
      await expect(api.startTask(blockedTask.id)).rejects.toThrow(/→ 409:/);

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
