import { test, expect } from "../fixtures";

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
    const uniqueTitle = `E2E Epic ${Date.now()}`;

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
    const storyTitle = `E2E Story ${Date.now()}`;
    await roadmapPage.newStoryButton.click();
    await roadmapPage.createStoryTitleInput.fill(storyTitle);
    await roadmapPage.createStoryDescriptionInput.fill("A story description");
    await roadmapPage.createStorySubmitButton.click();

    await roadmapPage.openStory(storyTitle);
    const taskTitle = `E2E Task ${Date.now()}`;
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

    const uniqueTitle = `E2E Actions ${Date.now()}`;
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
    const task = await api.createTask(story.id, {
      title: "Task for action test",
      description: "desc",
    });

    await roadmapPage.page.goto(`/tasks/${task.id}`);
    await expect(roadmapPage.taskDetailTitle).toContainText("Task for action test");
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

    const uniqueTitle = `E2E Delete ${Date.now()}`;
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
});
