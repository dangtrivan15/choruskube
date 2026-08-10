import { test, expect } from "../fixtures";
import { RoadmapTimelinePage } from "../pages/roadmap-timeline.page";
import { uniqueName } from "../helpers/api-client";

test.describe("Roadmap Timeline View", () => {
  test("reachable from the Roadmap list's nav link and shows a lane per Epic and a marker per Story", async ({
    roadmapPage,
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Timeline Epic"),
      description: "Epic for timeline view e2e",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Timeline Story"),
      description: "desc",
    });

    const timelinePage = new RoadmapTimelinePage(page);

    try {
      // Reachable from the Epic list's nav link, not just by direct URL — mirrors
      // roadmap-graph.spec.ts's "reachable from the Epic list" assertion for the Graph view.
      await roadmapPage.goto();
      await roadmapPage.page.getByTestId("roadmap-timeline-view-link").click();
      await expect(page).toHaveURL("/roadmap/timeline");
      await expect(timelinePage.heading).toBeVisible();

      await expect(timelinePage.laneByLabel(epic.title)).toBeVisible();
      await expect(timelinePage.markerByLabel(story.title)).toBeVisible();
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("reflects a Story created out-of-band without a manual refresh", async ({
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Timeline Live Update Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });

    const timelinePage = new RoadmapTimelinePage(page);

    try {
      await timelinePage.goto();
      await expect(timelinePage.laneByLabel(epic.title)).toBeVisible();

      // Simulate a second session creating a Story directly via the API — mirrors
      // roadmap-graph.spec.ts's "reflects a dependency created out-of-band without a manual
      // refresh" pattern (drive state via API, assert the already-open page updates via STOMP).
      const story = await api.createStory(epic.id, {
        title: uniqueName("Live Timeline Story"),
        description: "desc",
      });

      await expect(timelinePage.markerByLabel(story.title)).toBeVisible({ timeout: 15_000 });
    } finally {
      await api.deleteEpic(epic.id);
    }
  });
});
