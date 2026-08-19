import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

// Single-session flow: open the Story Board, drag a Story card to a new
// column, then reload and confirm the move persisted server-side. Mirrors
// task-board.spec.ts.
//
// Scoped down the same way roadmap-board.spec.ts/task-board.spec.ts are:
// this does not drive a two-browser-context live-update scenario (another
// viewer seeing the drag over the roadmap-items STOMP feed) — that's covered
// at the unit level by StoryBoardPage.test.tsx's STOMP-triggered-refetch test.
test.describe("Story Board", () => {
  test("displays board with columns", async ({ storyBoardPage }) => {
    await storyBoardPage.goto();
    await expect(storyBoardPage.column("backlog")).toBeVisible();
    await expect(storyBoardPage.column("in_progress")).toBeVisible();
    await expect(storyBoardPage.column("rolled_out")).toBeVisible();
  });

  // Switching ticket type keeps the *view* — Board stays Board across Epics/Stories/Tasks. These
  // four tests pin that both ways, since it is what replaced the per-board cross-links.
  test("switching the ticket type to Epics keeps the Board view", async ({ storyBoardPage, page }) => {
    await storyBoardPage.goto();
    await storyBoardPage.viewControls.selectTicketType("epic");
    await expect(page.getByTestId("roadmap-board-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/board$/);
  });

  test("switching the ticket type to Tasks keeps the Board view", async ({ storyBoardPage, page }) => {
    await storyBoardPage.goto();
    await storyBoardPage.viewControls.selectTicketType("task");
    await expect(page.getByTestId("task-board-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/board\/tasks$/);
  });

  test("List view opens the Story list, not the Epic list", async ({ storyBoardPage, page }) => {
    await storyBoardPage.goto();
    await storyBoardPage.listViewLink.click();
    await expect(page.getByTestId("story-list-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/stories$/);
  });

  test("Timeline is not offered for Stories at all", async ({ storyBoardPage }) => {
    await storyBoardPage.goto();
    // Not "disabled" — absent. There is no Story timeline page to send anyone to.
    await expect(storyBoardPage.viewControls.view("timeline")).toHaveCount(0);
  });

  test("switching the ticket type from the Epic board reaches the Story board", async ({
    roadmapBoardPage,
    page,
  }) => {
    await roadmapBoardPage.goto();
    await roadmapBoardPage.viewControls.selectTicketType("story");
    await expect(page.getByTestId("story-board-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/board\/stories$/);
  });

  test("switching the ticket type from the Task board reaches the Story board", async ({
    taskBoardPage,
    page,
  }) => {
    await taskBoardPage.goto();
    await taskBoardPage.viewControls.selectTicketType("story");
    await expect(page.getByTestId("story-board-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/board\/stories$/);
  });

  test("drag a story card to a legal target column and confirm the move persists on reload", async ({
    storyBoardPage,
    api,
    workerRepo,
  }) => {
    const uniqueTitle = uniqueName("E2E Board Story");
    const epic = await api.createEpic({
      title: `Epic for ${uniqueTitle}`,
      description: "Epic for the story board E2E test",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    await api.createStory(epic.id, {
      title: uniqueTitle,
      description: "Story for the story board E2E test",
    });

    await storyBoardPage.goto();
    await storyBoardPage.expectCardInColumn(uniqueTitle, "backlog");

    // Drag the card from Backlog into In Progress — a legal board stage
    // (updateStage has no transition whitelist, unlike Task's status
    // endpoint; any of the 3 board stages is a legal drop target).
    await storyBoardPage.dragCardToColumn(uniqueTitle, "in_progress");
    await storyBoardPage.expectCardInColumn(uniqueTitle, "in_progress");

    // Reload — the new stage is persisted server-side, not just client state.
    await storyBoardPage.goto();
    await storyBoardPage.expectCardInColumn(uniqueTitle, "in_progress");

    // No cleanup needed: stage moves don't block deletion the way a started descendant Task
    // does (Decision 2 — stage moves bypass that guard entirely), but this spec leaves the
    // fixture Epic/Story behind anyway, mirroring roadmap-board.spec.ts/task-board.spec.ts's
    // own no-cleanup convention. The `uniqueTitle` suffix keeps this fixture from colliding
    // with other runs of this spec.
  });
});
