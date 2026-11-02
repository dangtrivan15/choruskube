import type { Page } from "@playwright/test";
import { test, expect } from "../fixtures";
import { uniqueName, type TestApiClient } from "../helpers/api-client";
import type { RunMonitorPage } from "../pages/run-monitor.page";

/**
 * The conflict loop. A Task titled `[e2e-analyze:story_conflict]` makes mock-agent.sh's Analyze
 * find the Story spec wrong, which parks the Task run at Await Story Revision. The server's
 * reconciler (every 10s in e2e) opens a revision run on the Story; once its spec is approved as
 * v2, the reconciler resumes the Task run and Analyze plans again — `planned`, since the mock
 * never raises a conflict past iteration 1. A `[e2e-analyze:needs_spec]` Task instead parks at
 * Approve Task Spec, a gate that re-checks the Story spec version on approval.
 */

interface StartedTask {
  storyId: string;
  storyPath: string;
  taskTitle: string;
  runId: string;
}

/** A Task with the given Analyze marker under a Story designed to v1, with its run started. */
async function startTaskOnDesignedStory(
  api: TestApiClient,
  projectId: string,
  marker: "story_conflict" | "needs_spec",
): Promise<StartedTask> {
  const epic = await api.createEpic({
    title: uniqueName("E2E Story Conflict Epic"),
    description: "Epic for story-conflict.spec.ts",
    softwareProjectId: projectId,
  });
  const story = await api.createStory(epic.id, {
    title: uniqueName("E2E Story Conflict Story"),
    description: "Story for story-conflict.spec.ts",
  });
  const taskTitle = `${uniqueName("E2E Story Conflict Task")} [e2e-analyze:${marker}]`;
  const task = await api.createTask(story.id, { title: taskTitle, description: "desc" });

  await api.approveStorySpec(story.id);
  const runId = (await api.startTask(task.id)).latestRunId!;
  expect(runId).toBeTruthy();
  return { storyId: story.id, storyPath: `/roadmap/epics/${epic.id}/stories/${story.id}`, taskTitle, runId };
}

/** Cleanup: a design run left open parks at its approval gate for the rest of the suite. */
async function cancelOpenDesignRun(api: TestApiClient, storyId: string): Promise<void> {
  const open = (await api.getStory(storyId).catch(() => null))?.openDesignRunId;
  if (open) await api.cancelRun(open).catch(() => {});
}

async function waitForParkedOnConflict(api: TestApiClient, runId: string): Promise<void> {
  const analyze = await api.waitForNodeIteration(runId, "analyze", 1, ["completed"], 180_000);
  expect(analyze.decision).toBe("story_conflict");
  await api.waitForNodeStatus(runId, "await_story_revision", ["awaiting_human"], 60_000);
}

/** Polls until a revision run other than `previous` holds the conflict the Task run raised. */
async function waitForRevisionRun(
  api: TestApiClient,
  storyId: string,
  runId: string,
  previous = "",
): Promise<string> {
  let revisionRunId = "";
  await expect
    .poll(
      async () => {
        const conflicts = await api.getStoryConflicts(storyId);
        const held = conflicts.find((c) => c.taskRunId === runId)?.revisionRunId ?? "";
        revisionRunId = held === previous ? "" : held;
        return revisionRunId;
      },
      { timeout: 90_000, intervals: [2_000] },
    )
    .not.toBe("");
  return revisionRunId;
}

async function expectResumedOnV2(
  api: TestApiClient,
  page: Page,
  runMonitorPage: RunMonitorPage,
  runId: string,
): Promise<void> {
  const replanned = await api.waitForNodeIteration(runId, "analyze", 2, ["completed"], 180_000);
  expect(replanned.decision).toBe("planned");

  const run = await api.getRun(runId);
  const gate = run.nodeExecutions.find((ne) => ne.label === "await_story_revision");
  expect(gate?.decision).toBe("resume");

  await runMonitorPage.goto(runId);
  await expect(page.getByTestId("run-summary-spec-version").first()).toContainText("spec v2");
}

test.describe("Story conflict", () => {
  test("a conflict opens a Story revision, and approving it resumes the Task on spec v2", async ({
    page,
    api,
    cloneableProject,
    roadmapPage,
    runMonitorPage,
  }) => {
    test.setTimeout(600_000);

    const { storyId, storyPath, taskTitle, runId } = await startTaskOnDesignedStory(
      api,
      cloneableProject.id,
      "story_conflict",
    );

    try {
      await test.step("the conflict parks the Task run at Await Story Revision", async () => {
        await waitForParkedOnConflict(api, runId);

        await runMonitorPage.goto(runId);
        await runMonitorPage.selectNode("await_story_revision");
        await expect(page.getByTestId("await-story-revision-notice")).toBeVisible();
      });

      let revisionRunId = "";
      await test.step("a revision run opens on the Story, answering the conflict", async () => {
        revisionRunId = await waitForRevisionRun(api, storyId, runId);

        await page.goto(storyPath);
        await expect(roadmapPage.storySpecRevisionBanner).toBeVisible();
        await expect(roadmapPage.storySpecRevisionBanner).toContainText(taskTitle);
        await expect(
          roadmapPage.storySpecRevisionBanner.getByRole("link", { name: "revision run" }),
        ).toHaveAttribute("href", `/runs/${revisionRunId}`);
      });

      await test.step("approving the revision records spec v2", async () => {
        await api.approveDesignRun(revisionRunId);
        const spec = await api.waitForStorySpecVersion(storyId, 2, 90_000);
        // The revision edited the current spec the server staged for it, not a fresh draft.
        expect(spec.content).toContain("### v2 — ");
        expect(spec.content).not.toContain("No revisions yet.");

        await page.goto(storyPath);
        await expect(roadmapPage.storySpecVersionSelect).toContainText("v2");
        await expect(roadmapPage.storySpecRevisionBanner).toHaveCount(0);
      });

      await test.step("the Task run resumes and Analyze plans again", async () => {
        await expectResumedOnV2(api, page, runMonitorPage, runId);
      });
    } finally {
      // Left running, the resumed run would sit at Final Approval for the rest of the suite.
      await api.cancelRun(runId).catch(() => {});
      await cancelOpenDesignRun(api, storyId);
    }
  });

  test("a revision that is cancelled leaves its conflict on the Story page, and a manual Revise answers it", async ({
    page,
    api,
    cloneableProject,
    roadmapPage,
    runMonitorPage,
  }) => {
    test.setTimeout(600_000);

    const { storyId, storyPath, taskTitle, runId } = await startTaskOnDesignedStory(
      api,
      cloneableProject.id,
      "story_conflict",
    );
    let revisionRunId = "";

    try {
      let cancelledRunId = "";
      await test.step("the automatic revision run is cancelled", async () => {
        await waitForParkedOnConflict(api, runId);
        cancelledRunId = await waitForRevisionRun(api, storyId, runId);
        await api.cancelRun(cancelledRunId);
        await api.waitForRunStatus(cancelledRunId, ["cancelled"], 30_000);
      });

      await test.step("the Story page lists the conflict under the run that ended", async () => {
        await page.goto(storyPath);
        await expect(roadmapPage.storySpecStrandedConflicts).toBeVisible();
        await expect(roadmapPage.storySpecStrandedConflicts).toContainText(taskTitle);
        await expect(roadmapPage.storySpecStrandedConflicts).toContainText("Revise the spec to answer them");
        await expect(
          roadmapPage.storySpecStrandedConflicts.getByRole("link", { name: "revision run" }),
        ).toHaveAttribute("href", `/runs/${cancelledRunId}`);
        await expect(roadmapPage.storySpecRevisionBanner).toHaveCount(0);
      });

      await test.step("a manual Revise takes the conflict into its run", async () => {
        await roadmapPage.storySpecReviseButton.click();
        await roadmapPage.storySpecReviseGuidance.fill("Answer the conflict the cancelled revision left.");
        await roadmapPage.storySpecReviseSubmit.click();

        revisionRunId = await waitForRevisionRun(api, storyId, runId, cancelledRunId);
        await expect(roadmapPage.storySpecRevisionBanner).toBeVisible();
        await expect(roadmapPage.storySpecRevisionBanner).toContainText("It answers conflicts raised by");
        await expect(roadmapPage.storySpecRevisionBanner).toContainText(taskTitle);
        await expect(
          roadmapPage.storySpecRevisionBanner.getByRole("link", { name: "revision run" }),
        ).toHaveAttribute("href", `/runs/${revisionRunId}`);
        await expect(roadmapPage.storySpecStrandedConflicts).toHaveCount(0);
      });

      await test.step("approving the revision resumes the Task run on spec v2", async () => {
        await api.approveDesignRun(revisionRunId);
        await api.waitForStorySpecVersion(storyId, 2, 90_000);
        await expectResumedOnV2(api, page, runMonitorPage, runId);
      });
    } finally {
      await api.cancelRun(runId).catch(() => {});
      await cancelOpenDesignRun(api, storyId);
    }
  });

  test("approving a Task gate after its Story spec moved re-checks it instead", async ({
    page,
    api,
    cloneableProject,
    runMonitorPage,
  }) => {
    test.setTimeout(600_000);

    // needs_spec parks the run at Approve Task Spec, a gate that re-checks the Story spec version.
    const { storyId, runId } = await startTaskOnDesignedStory(api, cloneableProject.id, "needs_spec");
    let revisionRunId = "";

    try {
      await test.step("the Task run waits at Approve Task Spec, planned on spec v1", async () => {
        await api.waitForNodeStatus(runId, "approve_task_spec", ["awaiting_human"], 240_000);
      });

      await test.step("a manual Revise moves the Story spec to v2", async () => {
        revisionRunId = (await api.reviseStory(storyId, "Move the spec on while the Task waits at its gate.")).id;
        await api.approveDesignRun(revisionRunId);
        await api.waitForStorySpecVersion(storyId, 2, 90_000);
      });

      await test.step("the approval is turned into a re-check, and the gate opens again after it", async () => {
        await runMonitorPage.goto(runId);
        await runMonitorPage.selectNode("approve_task_spec");
        await runMonitorPage.approveGate();

        await expect(
          page
            .locator('[data-sonner-toast][data-type="warning"]')
            .filter({ hasText: "Not approved yet: The Story spec moved from v1 to v2 since this Task planned." }),
        ).toBeVisible();

        await expect
          .poll(
            async () =>
              (await api.getRun(runId)).nodeExecutions.find(
                (ne) => ne.label === "approve_task_spec" && ne.iteration === 1,
              )?.decision,
            { timeout: 30_000, intervals: [1_000] },
          )
          .toBe("rereview");
        await api.waitForNodeIteration(runId, "task_spec_review", 2, ["completed"], 180_000);
        await api.waitForNodeIteration(runId, "approve_task_spec", 2, ["awaiting_human"], 60_000);

        const run = await api.getRun(runId);
        expect(run.nodeExecutions.filter((ne) => ne.label === "implement")).toHaveLength(0);

        await runMonitorPage.goto(runId);
        await expect(page.getByTestId("run-summary-spec-version").first()).toContainText("spec v2");
      });
    } finally {
      await api.cancelRun(runId).catch(() => {});
      if (revisionRunId) await api.cancelRun(revisionRunId).catch(() => {});
    }
  });
});
