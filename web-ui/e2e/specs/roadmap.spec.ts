import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Roadmap drill-down", () => {
  test("displays roadmap page with heading", async ({ roadmapPage }) => {
    await roadmapPage.goto();
    await expect(roadmapPage.heading).toHaveText("Roadmap");
    await expect(roadmapPage.newEpicButton).toBeVisible();
  });

  test("opens create epic dialog", async ({ roadmapPage }) => {
    await roadmapPage.goto();
    await roadmapPage.openCreateDialog();

    await expect(roadmapPage.createTitleInput).toBeVisible();
    await expect(roadmapPage.createDescriptionInput).toBeVisible();
    await expect(roadmapPage.createSoftwareProjectSelect).toBeVisible();
    await expect(roadmapPage.createSubmitButton).toBeVisible();
  });

  test("create epic submit is disabled without required fields", async ({
    roadmapPage,
  }) => {
    await roadmapPage.goto();
    await roadmapPage.openCreateDialog();

    // Submit should be disabled initially
    await expect(roadmapPage.createSubmitButton).toBeDisabled();
  });

  test("create an Epic, drill into its Story, then its Task", async ({
    roadmapPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }
    const repo = repos.content[0];
    // Single-repo SoftwareProjects share the git_repo's id; the dropdown
    // labels them by `repoDisplayName(url)` (last 2 path segments).
    const projectName = repo.url
      .replace(/^https?:\/\/[^/]+\//, "")
      .replace(/\.git$/, "");
    const uniqueTitle = uniqueName("E2E Epic");

    await roadmapPage.goto();
    await roadmapPage.createEpic(
      uniqueTitle,
      "This is an E2E test epic description",
      projectName,
      "E2E test motivation",
    );

    // Wait for the epic to appear in the list
    await roadmapPage.page.waitForTimeout(1000);
    await roadmapPage.goto();

    // Open the epic and verify details
    await roadmapPage.openEpic(uniqueTitle);
    await expect(roadmapPage.epicDetailTitle).toContainText(uniqueTitle);
    await expect(roadmapPage.epicDetailDescription).toBeVisible();

    // Create a Story under it, then a Task under that Story.
    const storyTitle = uniqueName("E2E Story");
    await roadmapPage.newStoryButton.click();
    await roadmapPage.createStoryTitleInput.fill(storyTitle);
    await roadmapPage.createStoryDescriptionInput.fill("A story description");
    await roadmapPage.createStorySubmitButton.click();

    await roadmapPage.openStory(storyTitle);
    const taskTitle = uniqueName("E2E Task");
    await roadmapPage.newTaskButton.click();
    await roadmapPage.createTaskTitleInput.fill(taskTitle);
    await roadmapPage.createTaskDescriptionInput.fill("A task description");
    await roadmapPage.createTaskSubmitButton.click();

    await roadmapPage.openTask(taskTitle);
    await expect(roadmapPage.taskDetailTitle).toContainText(taskTitle);
    await expect(roadmapPage.taskStartButton).toBeVisible();

    // Clean up — deleting the Epic cascades to its Story and Task.
    await api.listEpics().then(async (epics) => {
      const created = epics.content.find((e) => e.title === uniqueTitle);
      if (created) await api.deleteEpic(created.id);
    });
  });

  test("task detail shows Start button for a backlog task", async ({
    roadmapPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const uniqueTitle = uniqueName("E2E Actions");
    // git_repo.id IS software_project.id post-V45 — pass it directly.
    const epic = await api.createEpic({
      title: uniqueTitle,
      description: "Testing action buttons",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, {
      title: "Story for action test",
      description: "desc",
    });
    const taskTitle = uniqueName("Task for action test");
    const task = await api.createTask(story.id, {
      title: taskTitle,
      description: "desc",
    });

    await roadmapPage.page.goto(`/tasks/${task.id}`);
    await expect(roadmapPage.taskDetailTitle).toContainText(taskTitle);
    await expect(roadmapPage.taskStartButton).toBeVisible();
    await expect(roadmapPage.taskDeleteButton).toBeVisible();

    // Clean up
    await api.deleteEpic(epic.id);
  });

  test("delete confirmation dialog works for an Epic", async ({ roadmapPage, api }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const uniqueTitle = uniqueName("E2E Delete");
    await api.createEpic({
      title: uniqueTitle,
      description: "Will be deleted",
      softwareProjectId: repos.content[0].id,
    });

    await roadmapPage.goto();
    await roadmapPage.openEpic(uniqueTitle);

    // Click delete
    await roadmapPage.epicDeleteButton.click();

    // Confirmation dialog should appear
    await expect(roadmapPage.deleteEpicConfirmButton).toBeVisible();
    await roadmapPage.deleteEpicConfirmButton.click();

    // Wait and verify it's gone
    await roadmapPage.page.waitForTimeout(2000);
    await roadmapPage.goto();

    // The epic should no longer appear
    const items = roadmapPage.epicItems.filter({ hasText: uniqueTitle });
    await expect(items).toHaveCount(0);
  });

  test("no route serves the old flat proposal list", async ({ page }) => {
    const response = await page.goto("/proposals");
    // The old flat list route no longer exists — it falls through to the
    // catch-all NotFoundPage, not a 404 HTTP response (this is a client-side SPA route).
    expect(response?.ok()).toBe(true);
    await expect(page.getByTestId("epic-list")).toHaveCount(0);
  });

  test("Story and Task list rows show a Blocked badge without opening the graph, and it clears without a manual refresh once the blocker completes", async ({
    roadmapPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: uniqueName("E2E List Readiness Epic"),
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("List Readiness Story"),
      description: "desc",
    });
    const blockingTask = await api.createTask(story.id, {
      title: uniqueName("List Readiness Blocking Task"),
      description: "desc",
    });
    const blockedTask = await api.createTask(story.id, {
      title: uniqueName("List Readiness Blocked Task"),
      description: "desc",
    });
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: blockingTask.id,
      blockedItemType: "task",
      blockedItemId: blockedTask.id,
    });
    // Readiness is computed per-item from its own direct/transitive dependency
    // edges (Part 1 §3.1 — "this feature does not change the algorithm"), not
    // rolled up from a Story's child Tasks. A Task-to-Task edge alone leaves
    // the parent Story itself un-blocked, so a second edge blocks the Story
    // directly — exactly how a user would flag "this Story can't start yet"
    // via the same dependency picker used for Task-to-Task edges.
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: blockingTask.id,
      blockedItemType: "story",
      blockedItemId: story.id,
    });

    // Not the graph — the Epic detail page's flat Story list.
    await roadmapPage.goto();
    await roadmapPage.openEpic(epic.title);
    await expect(roadmapPage.storyItemReadinessBadge(story.title)).toBeVisible();

    // Not the graph — the Story detail page's flat Task list.
    await roadmapPage.openStory(story.title);
    await expect(roadmapPage.taskItemReadinessBadge(blockedTask.title)).toBeVisible();
    await expect(roadmapPage.taskItemReadinessBadge(blockingTask.title)).not.toBeVisible();

    // Complete the blocking Task out-of-band (mirrors roadmap-graph.spec.ts's
    // equivalent pattern — completeTask only needs a terminal run, not a real
    // "Feature Development" template run) and confirm the Task row's badge
    // clears on the already-open Story detail page without a manual reload —
    // exercises the realtime push-driven update flow (Part 1 §4).
    const started = await api.startTask(blockingTask.id);
    expect(started.latestRunId).not.toBeNull();
    await api.waitForRunStatus(started.latestRunId!, ["running"], 15_000);
    await api.cancelRun(started.latestRunId!);
    await api.completeTask(blockingTask.id);

    await expect(roadmapPage.taskItemReadinessBadge(blockedTask.title)).not.toBeVisible();

    await roadmapPage.page.goto(`/roadmap/epics/${epic.id}`);
    await expect(roadmapPage.storyItemReadinessBadge(story.title)).not.toBeVisible();

    // No cleanup: starting blockingTask moved it out of backlog, and
    // DefaultEpicService#delete refuses to delete an Epic with any started
    // descendant Task (see run-lifecycle.spec.ts's breadcrumb test) — the
    // uniqueName() title keeps this fixture from colliding with concurrent runs.
  });

  test("'Ready to start' filter on the Roadmap list shows only Epics with unblocked work", async ({
    roadmapPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const readyEpic = await api.createEpic({
      title: uniqueName("E2E Ready Filter Ready Epic"),
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    await api.createStory(readyEpic.id, { title: "Unblocked story", description: "desc" });

    const blockedEpic = await api.createEpic({
      title: uniqueName("E2E Ready Filter Blocked Epic"),
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const blockedStory = await api.createStory(blockedEpic.id, {
      title: "Blocked story",
      description: "desc",
    });
    const blockerEpic = await api.createEpic({
      title: uniqueName("E2E Ready Filter Blocker Owner Epic"),
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const blockerStory = await api.createStory(blockerEpic.id, {
      title: "Blocker story",
      description: "desc",
    });
    await api.createDependency({
      blockingItemType: "story",
      blockingItemId: blockerStory.id,
      blockedItemType: "story",
      blockedItemId: blockedStory.id,
    });

    await roadmapPage.goto();
    await expect(roadmapPage.epicItems.filter({ hasText: readyEpic.title })).toBeVisible();
    await expect(roadmapPage.epicItems.filter({ hasText: blockedEpic.title })).toBeVisible();

    await roadmapPage.readyToStartToggle.click();

    await expect(roadmapPage.epicItems.filter({ hasText: readyEpic.title })).toBeVisible();
    await expect(roadmapPage.epicItems.filter({ hasText: blockedEpic.title })).toHaveCount(0);

    // Clean up.
    await api.deleteEpic(readyEpic.id);
    await api.deleteEpic(blockedEpic.id);
    await api.deleteEpic(blockerEpic.id);
  });
});
