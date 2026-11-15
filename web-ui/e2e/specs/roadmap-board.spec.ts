import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

// Single-session flow: open the board, expand a card to see its Stories, drag
// it to a new column, then reload and confirm the move persisted server-side.
//
// Scoped down from the full spec ask: this does not drive a two-browser-context
// live-update scenario (another viewer seeing the drag over the roadmap-items
// STOMP feed) — that's covered at the unit level by RoadmapBoardPage.test.tsx's
// STOMP-triggered-refetch test, and a reliable two-context E2E rendition of it
// was judged not worth the flake risk for this pass.
test.describe("Roadmap Board", () => {
  test("displays board with columns", async ({ roadmapBoardPage }) => {
    await roadmapBoardPage.goto();
    await expect(roadmapBoardPage.column("backlog")).toBeVisible();
    await expect(roadmapBoardPage.column("in_progress")).toBeVisible();
    await expect(roadmapBoardPage.column("rolled_out")).toBeVisible();
  });

  test("List view link returns to the Epic list", async ({ roadmapBoardPage, page }) => {
    await roadmapBoardPage.goto();
    await roadmapBoardPage.listViewLink.click();
    await expect(page.getByTestId("roadmap-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap$/);
  });

  test("Board view button from the Epic list opens the board", async ({ roadmapPage, page }) => {
    await roadmapPage.goto();
    await roadmapPage.boardViewLink.click();
    await expect(page.getByTestId("roadmap-board-heading")).toBeVisible();
    await expect(page).toHaveURL(/\/roadmap\/board$/);
  });

  test("expand a card, drag it to a new column, and confirm the move persists on reload", async ({
    roadmapBoardPage,
    api,
    workerRepo,
  }) => {
    const uniqueTitle = uniqueName("E2E Board Epic");
    const epic = await api.createEpic({
      title: uniqueTitle,
      description: "Epic for the roadmap board E2E test",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Board test story"),
      description: "A story shown under the expanded card",
    });

    try {
      await roadmapBoardPage.goto();
      await roadmapBoardPage.expectCardInColumn(uniqueTitle, "backlog");

      // Expand — the Story appears with its own mini progress.
      await roadmapBoardPage.expandCard(uniqueTitle);
      await expect(roadmapBoardPage.storyRowsFor(uniqueTitle)).toContainText(story.title);

      // Drag the card from Backlog into In Progress.
      await roadmapBoardPage.dragCardToColumn(uniqueTitle, "in_progress");
      await roadmapBoardPage.expectCardInColumn(uniqueTitle, "in_progress");

      // Reload — the new stage is persisted server-side, not just client state.
      await roadmapBoardPage.goto();
      await roadmapBoardPage.expectCardInColumn(uniqueTitle, "in_progress");
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  // Scoped as its own test rather than toggling mid-assertion inside the drag spec
  // above: exercises the boardEpicsQueryKey/useUpdateEpicStage fix from Task 9 (the
  // stage-update mutation must target the currently-active, filtered cache entry),
  // not just UI-level filtering.
  test("drag a card to a new column with the 'Ready to start' toggle on, and confirm the move persists on reload", async ({
    roadmapBoardPage,
    api,
    cloneableProject,
  }) => {
    test.setTimeout(300_000);
    const uniqueTitle = uniqueName("E2E Board Ready Drag Epic");
    const epic = await api.createEpic({
      title: uniqueTitle,
      description: "Epic for the ready-filtered drag E2E test",
      softwareProjectId: cloneableProject.id,
    });
    const story = await api.createStory(epic.id, { title: "Unblocked story", description: "desc" });
    // Backlog work under a designed Story, or the Epic has nothing startable and the toggle below
    // hides the very card this spec drags.
    await api.createTask(story.id, { title: "Unblocked task", description: "desc" });
    await api.approveStorySpec(story.id);

    try {
      await roadmapBoardPage.goto();
      await roadmapBoardPage.readyToStartToggle.click();
      await roadmapBoardPage.expectCardInColumn(uniqueTitle, "backlog");

      await roadmapBoardPage.dragCardToColumn(uniqueTitle, "in_progress");
      await roadmapBoardPage.expectCardInColumn(uniqueTitle, "in_progress");

      // Reload (the toggle resets, since it's local component state, not URL state) —
      // the new stage must still be persisted server-side, not just client state.
      await roadmapBoardPage.goto();
      await roadmapBoardPage.expectCardInColumn(uniqueTitle, "in_progress");
    } finally {
      await api.deleteEpic(epic.id);
    }
  });
});
