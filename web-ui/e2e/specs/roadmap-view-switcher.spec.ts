import { test, expect } from "../fixtures";
import { RoadmapBoardPage } from "../pages/roadmap-board.page";
import { RoadmapTimelinePage } from "../pages/roadmap-timeline.page";
import { RoadmapGraphPage } from "../pages/roadmap-graph.page";
import { uniqueName } from "../helpers/api-client";

// Covers §6's E2E scenario for the RoadmapViewSwitcher: focus set in one view survives a switch
// to another (Graph → Board, Board → Timeline), and Timeline's Graph entry stays disabled until
// something is actually focused (Decision 3). RoadmapTimelinePage has no dedicated fixture yet
// (see roadmap-timeline.spec.ts) — constructed directly from `page`, matching that file's convention.
test.describe("Roadmap View Switcher", () => {
  test("Graph → Board carries Epic+Story focus: the Epic's card is scrolled into view, highlighted, and expanded to the same Story", async ({
    roadmapGraphPage,
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Switcher Graph-to-Board Epic"),
      description: "Epic for the view switcher e2e",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Switcher Story"),
      description: "desc",
    });

    const boardPage = new RoadmapBoardPage(page);

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(story.title);

      await roadmapGraphPage.viewSwitcherBoardLink.click();
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
      title: uniqueName("E2E Switcher Board-to-Timeline Epic"),
      description: "Epic for the view switcher e2e",
      softwareProjectId: workerRepo.gitRepo.id,
    });

    const timelinePage = new RoadmapTimelinePage(page);

    try {
      await roadmapBoardPage.goto();
      await roadmapBoardPage.cardByTitle(epic.title).click();
      await expect(page).toHaveURL(new RegExp(`/roadmap/board\\?epic=${epic.id}`));

      await roadmapBoardPage.viewSwitcherTimelineLink.click();
      await expect(page).toHaveURL(new RegExp(`/roadmap/timeline\\?epic=${epic.id}`));
      await expect(timelinePage.heading).toBeVisible();

      await expect(timelinePage.laneByLabel(epic.title)).toBeVisible();
      await expect(timelinePage.laneByLabel(epic.title)).toHaveAttribute("data-focused", "true");
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("Timeline: Graph entry is disabled with nothing focused, then enables and navigates once an Epic lane is clicked", async ({
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Switcher Timeline Graph-Enable Epic"),
      description: "Epic for the view switcher e2e",
      softwareProjectId: workerRepo.gitRepo.id,
    });

    const timelinePage = new RoadmapTimelinePage(page);
    const graphPage = new RoadmapGraphPage(page);

    try {
      await timelinePage.goto();
      await expect(timelinePage.viewSwitcherGraphEntry).toBeDisabled();

      await timelinePage.laneByLabel(epic.title).click();
      await expect(page).toHaveURL(new RegExp(`/roadmap/timeline\\?epic=${epic.id}`));
      await expect(timelinePage.viewSwitcherGraphEntry).toBeEnabled();

      await timelinePage.viewSwitcherGraphEntry.click();
      await expect(page).toHaveURL(`/roadmap/epics/${epic.id}/graph`);
      await expect(graphPage.heading).toBeVisible();
    } finally {
      await api.deleteEpic(epic.id);
    }
  });
});
