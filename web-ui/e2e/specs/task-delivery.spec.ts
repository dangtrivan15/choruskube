import { test, expect } from "../fixtures";
import { uniqueName, type TestApiClient, type Task } from "../helpers/api-client";

/**
 * Starting a Task runs E2eTestDataSeeder's mock copy of Task Delivery. mock-agent.sh's analyze
 * scenario decides on the marker in the Task's title — `[e2e-analyze:planned]` goes straight to
 * Implement, `[e2e-analyze:needs_spec]` drafts a Task spec for review and approval first (driven
 * through its Approve Task Spec gate by story-conflict.spec.ts).
 */

/** A `[e2e-analyze:planned]` Task under a Story whose spec is approved, so it can start. */
async function startableTask(
  api: TestApiClient,
  projectId: string,
): Promise<{ task: Task; specVersion: number }> {
  const epic = await api.createEpic({
    title: uniqueName("E2E Task Delivery Epic"),
    description: "Epic for task-delivery.spec.ts",
    softwareProjectId: projectId,
  });
  const story = await api.createStory(epic.id, {
    title: uniqueName("E2E Task Delivery Story"),
    description: "Story for task-delivery.spec.ts",
  });
  const task = await api.createTask(story.id, {
    title: `${uniqueName("E2E Task Delivery Task")} [e2e-analyze:planned]`,
    description: "Task for task-delivery.spec.ts",
  });
  const spec = await api.approveStorySpec(story.id);
  return { task, specVersion: spec.version };
}

test.describe("Task Delivery", () => {
  test("the planned marker takes the run from Analyze straight to Implement", async ({
    page,
    api,
    cloneableProject,
    roadmapPage,
    runMonitorPage,
  }) => {
    test.setTimeout(360_000);
    const { task, specVersion } = await startableTask(api, cloneableProject.id);

    await page.goto(`/tasks/${task.id}`);
    await expect(roadmapPage.taskStartButton).toBeEnabled();
    await roadmapPage.taskStartButton.click();
    await roadmapPage.startTaskConfirmButton.click();
    await expect(roadmapPage.taskDetailStatus).toHaveText(/in progress/);

    const runId = (await api.listTasks(task.storyId)).find((t) => t.id === task.id)?.latestRunId;
    expect(runId).toBeTruthy();
    try {
      const analyze = await api.waitForNodeStatus(runId!, "analyze", ["completed"], 180_000);
      expect(analyze.decision).toBe("planned");
      await api.waitForNodeStatus(runId!, "implement", ["running", "completed"], 180_000);

      const run = await api.getRun(runId!);
      expect(run.nodeExecutions.filter((ne) => ne.label === "task_spec_review")).toHaveLength(0);

      await runMonitorPage.goto(runId!);
      await expect(page.getByTestId("run-summary-spec-version").first()).toContainText(`spec v${specVersion}`);
    } finally {
      // Left running, it would sit at Final Approval for the rest of the suite.
      await api.cancelRun(runId!).catch(() => {});
    }
  });

  test("a Task under an undesigned Story cannot start, and says why", async ({
    page,
    api,
    workerRepo,
    roadmapPage,
  }) => {
    // Nothing runs here, so the uncloneable workerRepo is enough.
    const epic = await api.createEpic({
      title: uniqueName("E2E Undesigned Epic"),
      description: "Epic for task-delivery.spec.ts",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("E2E Undesigned Story"),
      description: "Story for task-delivery.spec.ts",
    });
    const task = await api.createTask(story.id, {
      title: uniqueName("E2E Undesigned Task"),
      description: "Task for task-delivery.spec.ts",
    });

    try {
      await page.goto(`/tasks/${task.id}`);
      await expect(roadmapPage.taskStartButton).toBeVisible();
      await expect(roadmapPage.taskStartButton).toBeDisabled();
      await expect(roadmapPage.taskStartReason).toContainText("Story spec not approved yet");
      await expect(roadmapPage.taskStartButton).toHaveAccessibleDescription(/Story spec not approved yet/);
      await expect(roadmapPage.taskDesignStoryButton).toBeVisible();

      await expect(api.startTask(task.id)).rejects.toThrow(
        /→ 409:.*Cannot start this Task: Story spec not approved yet/,
      );
    } finally {
      // Nothing started, so the Epic can still be deleted.
      await api.deleteEpic(epic.id);
    }
  });
});
