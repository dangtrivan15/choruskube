import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

/**
 * The conflict loop. A Task titled `[e2e-analyze:story_conflict]` makes mock-agent.sh's Analyze
 * find the Story spec wrong, which parks the Task run at Await Story Revision. The server's
 * reconciler (every 10s in e2e) opens a revision run on the Story; once its spec is approved as
 * v2, the reconciler resumes the Task run and Analyze plans again — `planned`, since the mock
 * never raises a conflict past iteration 1.
 */
test.describe("Story conflict", () => {
  test("a conflict opens a Story revision, and approving it resumes the Task on spec v2", async ({
    page,
    api,
    cloneableProject,
    roadmapPage,
    runMonitorPage,
  }) => {
    test.setTimeout(600_000);

    const epic = await api.createEpic({
      title: uniqueName("E2E Story Conflict Epic"),
      description: "Epic for story-conflict.spec.ts",
      softwareProjectId: cloneableProject.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("E2E Story Conflict Story"),
      description: "Story for story-conflict.spec.ts",
    });
    const taskTitle = `${uniqueName("E2E Story Conflict Task")} [e2e-analyze:story_conflict]`;
    const task = await api.createTask(story.id, { title: taskTitle, description: "desc" });
    const storyPath = `/roadmap/epics/${epic.id}/stories/${story.id}`;

    await api.approveStorySpec(story.id);
    const runId = (await api.startTask(task.id)).latestRunId!;
    expect(runId).toBeTruthy();

    try {
      await test.step("the conflict parks the Task run at Await Story Revision", async () => {
        const analyze = await api.waitForNodeIteration(runId, "analyze", 1, ["completed"], 180_000);
        expect(analyze.decision).toBe("story_conflict");
        await api.waitForNodeStatus(runId, "await_story_revision", ["awaiting_human"], 60_000);

        await runMonitorPage.goto(runId);
        await runMonitorPage.selectNode("await_story_revision");
        await expect(page.getByTestId("await-story-revision-notice")).toBeVisible();
      });

      let revisionRunId = "";
      await test.step("a revision run opens on the Story, answering the conflict", async () => {
        await expect
          .poll(
            async () => {
              const conflicts = await api.getStoryConflicts(story.id);
              revisionRunId = conflicts.find((c) => c.taskRunId === runId)?.revisionRunId ?? "";
              return revisionRunId;
            },
            { timeout: 90_000, intervals: [2_000] },
          )
          .not.toBe("");

        await page.goto(storyPath);
        await expect(roadmapPage.storySpecRevisionBanner).toBeVisible();
        await expect(roadmapPage.storySpecRevisionBanner).toContainText(taskTitle);
        await expect(
          roadmapPage.storySpecRevisionBanner.getByRole("link", { name: "revision run" }),
        ).toHaveAttribute("href", `/runs/${revisionRunId}`);
      });

      await test.step("approving the revision records spec v2", async () => {
        await api.approveDesignRun(revisionRunId);
        const spec = await api.waitForStorySpecVersion(story.id, 2, 90_000);
        // The revision edited the current spec the server staged for it, not a fresh draft.
        expect(spec.content).toContain("### v2 — ");
        expect(spec.content).not.toContain("No revisions yet.");

        await page.goto(storyPath);
        await expect(roadmapPage.storySpecVersionSelect).toContainText("v2");
        await expect(roadmapPage.storySpecRevisionBanner).toHaveCount(0);
      });

      await test.step("the Task run resumes and Analyze plans again", async () => {
        const replanned = await api.waitForNodeIteration(runId, "analyze", 2, ["completed"], 180_000);
        expect(replanned.decision).toBe("planned");

        const run = await api.getRun(runId);
        const gate = run.nodeExecutions.find((ne) => ne.label === "await_story_revision");
        expect(gate?.decision).toBe("resume");

        await runMonitorPage.goto(runId);
        await expect(page.getByTestId("run-summary-spec-version").first()).toContainText("spec v2");
      });
    } finally {
      // Left running, the resumed run would sit at Final Approval for the rest of the suite.
      await api.cancelRun(runId).catch(() => {});
    }
  });
});
