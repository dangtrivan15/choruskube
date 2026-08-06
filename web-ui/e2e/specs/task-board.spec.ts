import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

// Single-session flow: open the Task Board, drag a Task card to a new
// column, then reload and confirm the move persisted server-side.
//
// Scoped down the same way roadmap-board.spec.ts is: this does not drive a
// two-browser-context live-update scenario (another viewer seeing the drag
// over the roadmap-items STOMP feed) — that's covered at the unit level by
// TaskBoardPage.test.tsx's STOMP-triggered-refetch test.
test.describe("Task Board", () => {
  test("displays board with columns", async ({ taskBoardPage }) => {
    await taskBoardPage.goto();
    await expect(taskBoardPage.column("backlog")).toBeVisible();
    await expect(taskBoardPage.column("in_progress")).toBeVisible();
    await expect(taskBoardPage.column("done")).toBeVisible();
  });

  test("Epic board link opens the Roadmap (Epic) board", async ({ taskBoardPage, page }) => {
    await taskBoardPage.goto();
    await taskBoardPage.epicBoardLink.click();
    await expect(page.getByTestId("roadmap-board-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/board$/);
  });

  test("List view link opens the Epic list", async ({ taskBoardPage, page }) => {
    await taskBoardPage.goto();
    await taskBoardPage.listViewLink.click();
    await expect(page.getByTestId("roadmap-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap$/);
  });

  test("Task board link from the Epic board opens the Task board", async ({
    roadmapBoardPage,
    page,
  }) => {
    await roadmapBoardPage.goto();
    await page.getByTestId("roadmap-board-task-board-link").click();
    await expect(page.getByTestId("task-board-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/board\/tasks$/);
  });

  test("drag a task card to a legal target column and confirm the move persists on reload", async ({
    taskBoardPage,
    api,
    workerRepo,
  }) => {
    const uniqueTitle = uniqueName("E2E Board Task");
    const epic = await api.createEpic({
      title: `Epic for ${uniqueTitle}`,
      description: "Epic for the task board E2E test",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: "Task board test story",
      description: "A story holding the fixture task",
    });
    await api.createTask(story.id, {
      title: uniqueTitle,
      description: "Task for the task board E2E test",
    });

    await taskBoardPage.goto();
    await taskBoardPage.expectCardInColumn(uniqueTitle, "backlog");

    // Drag the card from Backlog into In Progress — a legal transition
    // (backlog -> in_progress) per TaskController's validated-transition
    // status endpoint; a move out of "done" has no legal target, so this
    // spec doesn't attempt one.
    await taskBoardPage.dragCardToColumn(uniqueTitle, "in_progress");
    await taskBoardPage.expectCardInColumn(uniqueTitle, "in_progress");

    // Reload — the new status is persisted server-side, not just client state.
    await taskBoardPage.goto();
    await taskBoardPage.expectCardInColumn(uniqueTitle, "in_progress");

    // No cleanup (mirrors run-lifecycle.spec.ts's breadcrumb test): the drag
    // above has already moved the Task out of "backlog" via a real started run,
    // and DefaultEpicService#delete deliberately refuses to delete an Epic with
    // any started descendant Task ("Can only delete an Epic while all of its
    // Tasks are still in backlog") — intentional, to preserve run history. The
    // `uniqueTitle` suffix keeps this fixture from colliding with other runs
    // of this spec, so leaving it behind is safe.
  });
});
