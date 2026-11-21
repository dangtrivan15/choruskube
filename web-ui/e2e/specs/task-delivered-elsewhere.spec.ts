import { test, expect, seededRepos } from "../fixtures";
import { uniqueName, type GitRepo, type TestApiClient } from "../helpers/api-client";

/**
 * Closing a Task as delivered elsewhere starts a built-in Delivery check run: its one gate,
 * `confirm_delivery`, approves itself as it opens and has the Worker read each listed pull request.
 * WireMock's github-pulls.json answers PR 7001 as merged into `main` for mock-frontend and as open
 * for mock-repo, so the same number drives both outcomes.
 *
 * The verified projects are single-member RepoGroups over the seeded repos, not `workerRepo`: a
 * pull request must belong to a repository of the Task's project, and `workerRepo`'s URL is not on
 * github.com. Nothing here designs the Story — the delivery check skips the Story-design and
 * blocker checks a Task start applies.
 */

/** A seeded repo's pull request URL, as a person would paste it from GitHub. */
function pullRequestUrl(repo: GitRepo, number: number): string {
  return `${repo.url.replace(/\/+$/, "").replace(/\.git$/, "")}/pull/${number}`;
}

async function seededRepo(api: TestApiClient, name: "mock-repo" | "mock-frontend"): Promise<GitRepo> {
  const repo = seededRepos((await api.listGitRepos()).content).find((r) => r.url.endsWith(`/e2e-test/${name}`));
  if (!repo) throw new Error(`E2eTestDataSeeder must seed e2e-test/${name} for this spec`);
  return repo;
}

/** A backlog Task under a fresh Epic and Story on `softwareProjectId`. */
async function backlogTask(api: TestApiClient, softwareProjectId: string, label: string) {
  const epic = await api.createEpic({
    title: uniqueName(`E2E Delivered ${label} Epic`),
    description: "Epic for task-delivered-elsewhere.spec.ts",
    softwareProjectId,
  });
  const story = await api.createStory(epic.id, {
    title: uniqueName(`E2E Delivered ${label} Story`),
    description: "Story for task-delivered-elsewhere.spec.ts",
  });
  const task = await api.createTask(story.id, {
    title: uniqueName(`E2E Delivered ${label} Task`),
    description: "Task for task-delivered-elsewhere.spec.ts",
  });
  return { epic, story, task };
}

test.describe("Close a Task as delivered elsewhere", () => {
  test("a merged pull request closes the Task through its delivery-check run", async ({
    page,
    api,
    roadmapPage,
  }) => {
    test.setTimeout(240_000);
    const mockFrontend = await seededRepo(api, "mock-frontend");
    const group = await api.createRepoGroup({
      name: uniqueName("e2e-delivered-merged"),
      memberRepoIds: [mockFrontend.id],
    });
    const { task } = await backlogTask(api, group.id, "Merged");

    await page.goto(`/tasks/${task.id}`);
    await roadmapPage.taskCloseDeliveredButton.click();
    await expect(roadmapPage.closeDeliveredDialog).toBeVisible();
    await expect(roadmapPage.closeDeliveredSubmit).toBeDisabled();

    await roadmapPage.closeDeliveredPullRequests.fill(`${pullRequestUrl(mockFrontend, 7001)}\n\n`);
    await roadmapPage.closeDeliveredNote.fill("Shipped through a hotfix branch outside ChorusKube.");
    await roadmapPage.closeDeliveredSubmit.click();

    await expect(roadmapPage.closeDeliveredDialog).toBeHidden();
    await expect(page.locator("[data-sonner-toast]").filter({ hasText: "Delivery check started" })).toBeVisible();

    const done = await api.waitForTaskStatus(task.id, ["done"], 180_000);
    const runId = done.latestRunId;
    expect(runId).toBeTruthy();
    expect((await api.getRun(runId!)).status).toBe("completed");

    await expect(roadmapPage.taskDetailStatus).toHaveText("done", { timeout: 30_000 });
    await expect(roadmapPage.taskCloseDeliveredButton).toBeHidden();
    const runLink = roadmapPage.taskRunHistoryList.locator(`a[href="/runs/${runId}"]`);
    await expect(runLink).toBeVisible({ timeout: 30_000 });
    await expect(runLink).toContainText("Delivery check");

    // No cleanup: a Task that ran keeps its Epic and RepoGroup from being deleted cleanly, and
    // uniqueName() keeps them apart from every other test's.
  });

  test("an unmerged pull request holds the gate with the reason and leaves the Task in progress", async ({
    page,
    api,
    runMonitorPage,
  }) => {
    test.setTimeout(240_000);
    const mockRepo = await seededRepo(api, "mock-repo");
    const group = await api.createRepoGroup({
      name: uniqueName("e2e-delivered-open"),
      memberRepoIds: [mockRepo.id],
    });
    const { task } = await backlogTask(api, group.id, "Open");
    const note = "Landed through an external pull request.";

    const started = await api.closeTaskAsDelivered(task.id, [pullRequestUrl(mockRepo, 7001)], note);
    expect(started.status).toBe("in_progress");
    const runId = started.latestRunId;
    expect(runId).toBeTruthy();

    try {
      // Waiting on the gate alone would pass on its first, pre-check awaiting_human.
      await expect(async () => {
        const stamps = await api.getGateStamps(runId!);
        expect(stamps.some((s) => s.gateLabel === "confirm_delivery" && s.status === "voided")).toBe(true);
      }).toPass({ timeout: 180_000, intervals: [2_000] });
      await api.waitForNodeStatus(runId!, "confirm_delivery", ["awaiting_human"], 30_000);

      await runMonitorPage.goto(runId!);
      await runMonitorPage.selectNode("confirm_delivery");
      await expect(page.getByTestId("gate-held-voided-notice")).toContainText("is not merged");
      await expect(runMonitorPage.runSummary.getByTestId("run-summary-prompt").first()).toContainText(note);
      await expect(page.getByTestId("pull-request-link")).toHaveCount(1);

      const run = await api.getRun(runId!);
      expect(run.status).not.toBe("completed");
      const after = await api.getTask(task.id);
      expect(after.status).toBe("in_progress");
      expect(after.latestRunId).toBe(runId);
    } finally {
      // Left open, the gate would sit on every approvals page for the rest of the suite.
      await api.cancelRun(runId!).catch(() => {});
    }
  });

  test("a pull request outside the Task's project is refused before any run starts", async ({
    api,
    page,
    workerRepo,
    roadmapPage,
  }) => {
    const mockRepo = await seededRepo(api, "mock-repo");
    const { epic, task } = await backlogTask(api, workerRepo.gitRepo.id, "Outside");
    const outsideUrl = pullRequestUrl(mockRepo, 7001);

    try {
      await expect(
        api.closeTaskAsDelivered(task.id, [outsideUrl], "Delivered in a repository this Task does not span."),
      ).rejects.toThrow(/→ 400:/);

      await page.goto(`/tasks/${task.id}`);
      await roadmapPage.taskCloseDeliveredButton.click();
      await roadmapPage.closeDeliveredPullRequests.fill(outsideUrl);
      await roadmapPage.closeDeliveredNote.fill("Delivered in a repository this Task does not span.");
      await roadmapPage.closeDeliveredSubmit.click();
      await expect(roadmapPage.closeDeliveredError).toBeVisible();
      await expect(roadmapPage.closeDeliveredError).not.toHaveText("Failed to start the delivery check.");
      await expect(roadmapPage.closeDeliveredDialog).toBeVisible();

      const after = await api.getTask(task.id);
      expect(after.status).toBe("backlog");
      expect(after.latestRunId).toBeNull();
    } finally {
      // Nothing started, so the Epic can still be deleted.
      await api.deleteEpic(epic.id);
    }
  });
});
