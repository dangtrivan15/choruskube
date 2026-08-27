import { test, expect } from "../fixtures";
import { RoadmapBoardPage } from "../pages/roadmap-board.page";
import { RoadmapTimelinePage } from "../pages/roadmap-timeline.page";
import { RoadmapGraphPage } from "../pages/roadmap-graph.page";
import { uniqueName } from "../helpers/api-client";

// Covers's E2E scenario for the shared Roadmap header (RoadmapViewControls): focus set in one
// view survives a switch to another (Graph -> Board, Board -> Timeline), the Graph action stays
// disabled until something is actually focused, and changing the ticket type keeps
// the current view where the new type has one and falls back to Board where it doesn't.
// RoadmapTimelinePage has no dedicated fixture yet (see roadmap-timeline.spec.ts) — constructed
// directly from `page`, matching that file's convention.
test.describe("Roadmap View Controls", () => {
  test("Graph → Board carries Epic+Story focus: the Epic's card is scrolled into view, highlighted, and expanded to the same Story", async ({
    roadmapGraphPage,
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Controls Graph-to-Board Epic"),
      description: "Epic for the view controls e2e",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Controls Story"),
      description: "desc",
    });

    const boardPage = new RoadmapBoardPage(page);

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(story.title);

      await roadmapGraphPage.boardViewLink.click();
      await expect(page).toHaveURL(new RegExp(`/roadmap/board\\?epic=${epic.id}&story=${story.id}`));
      await expect(boardPage.heading).toBeVisible();

      const card = boardPage.cardByTitle(epic.title);
      await expect(card).toBeVisible();
      await expect(card).toHaveAttribute("data-focused", "true");
      await expect(boardPage.storyRowsFor(epic.title)).toContainText(story.title);
      await expect(boardPage.storyRowsFor(epic.title)).toHaveAttribute("data-focused", "true");
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("Board → Timeline carries Epic focus: that Epic's lane is centered and highlighted", async ({
    roadmapBoardPage,
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Controls Board-to-Timeline Epic"),
      description: "Epic for the view controls e2e",
      softwareProjectId: workerRepo.gitRepo.id,
    });

    const timelinePage = new RoadmapTimelinePage(page);

    try {
      await roadmapBoardPage.goto();
      await roadmapBoardPage.focusCard(epic.title);
      await expect(page).toHaveURL(new RegExp(`/roadmap/board\\?epic=${epic.id}`));

      await roadmapBoardPage.timelineViewLink.click();
      await expect(page).toHaveURL(new RegExp(`/roadmap/timeline\\?epic=${epic.id}`));
      await expect(timelinePage.heading).toBeVisible();

      await expect(timelinePage.laneByLabel(epic.title)).toBeVisible();
      await expect(timelinePage.laneByLabel(epic.title)).toHaveAttribute("data-focused", "true");
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("Timeline: the Graph action is disabled with nothing focused, then enables and navigates once an Epic lane is clicked", async ({
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Controls Timeline Graph-Enable Epic"),
      description: "Epic for the view controls e2e",
      softwareProjectId: workerRepo.gitRepo.id,
    });

    const timelinePage = new RoadmapTimelinePage(page);
    const graphPage = new RoadmapGraphPage(page);

    try {
      await timelinePage.goto();
      await expect(timelinePage.graphAction).toBeDisabled();

      await timelinePage.laneByLabel(epic.title).click();
      await expect(page).toHaveURL(new RegExp(`/roadmap/timeline\\?epic=${epic.id}`));
      await expect(timelinePage.graphAction).toBeEnabled();

      await timelinePage.graphAction.click();
      await expect(page).toHaveURL(`/roadmap/epics/${epic.id}/graph`);
      await expect(graphPage.heading).toBeVisible();
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("changing the ticket type keeps the current view where the new type has one", async ({
    roadmapPage,
    page,
  }) => {
    await roadmapPage.goto();
    await roadmapPage.viewControls.selectTicketType("story");
    await expect(page).toHaveURL(/\/roadmap\/stories$/);
    await expect(page.getByTestId("story-list-heading")).toBeVisible();

    await roadmapPage.viewControls.selectTicketType("task");
    await expect(page).toHaveURL(/\/roadmap\/tasks$/);
    await expect(page.getByTestId("task-list-heading")).toBeVisible();
  });

  test("changing the ticket type falls back to Board when the current view has no page for it", async ({
    page,
  }) => {
    const timelinePage = new RoadmapTimelinePage(page);

    await timelinePage.goto();
    // Timeline is Epic-only, so Stories cannot keep it — the reader lands on the Story board
    // rather than on a dead route or a disabled control.
    await timelinePage.viewControls.selectTicketType("story");
    await expect(page).toHaveURL(/\/roadmap\/board\/stories$/);
    await expect(page.getByTestId("story-board-heading")).toBeVisible();
  });
});
