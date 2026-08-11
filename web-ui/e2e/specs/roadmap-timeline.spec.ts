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

  test("a Story blocked by an unfinished dependency shows the blocked badge", async ({
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Timeline Blocked Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const blocking = await api.createStory(epic.id, {
      title: uniqueName("Timeline Blocking Story"),
      description: "desc",
    });
    const blocked = await api.createStory(epic.id, {
      title: uniqueName("Timeline Blocked Story"),
      description: "desc",
    });
    await api.createDependency({
      blockingItemType: "story",
      blockingItemId: blocking.id,
      blockedItemType: "story",
      blockedItemId: blocked.id,
    });

    const timelinePage = new RoadmapTimelinePage(page);

    try {
      await timelinePage.goto();

      const blockedMarker = timelinePage.markerByLabel(blocked.title);
      await expect(blockedMarker).toBeVisible();
      await expect(blockedMarker.getByTestId("roadmap-timeline-story-blocked-badge")).toBeVisible();
      expect(await timelinePage.riskFor(blocked.title)).toBe("blocked");

      const blockingMarker = timelinePage.markerByLabel(blocking.title);
      await expect(blockingMarker.getByTestId("roadmap-timeline-story-blocked-badge")).toHaveCount(0);
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("an on-track Epic with no blocked or stalled work shows no risk badge", async ({
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Timeline Clean Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("Timeline Clean Story"),
      description: "desc",
    });

    const timelinePage = new RoadmapTimelinePage(page);

    try {
      await timelinePage.goto();

      await expect(timelinePage.laneByLabel(epic.title)).toBeVisible();
      expect(await timelinePage.riskFor(epic.title)).toBe("none");
      expect(await timelinePage.riskFor(story.title)).toBe("none");
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("adding a dependency out-of-band flips a Story to blocked live, no reload", async ({
    api,
    workerRepo,
    page,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Timeline Live Blocked Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const blocking = await api.createStory(epic.id, {
      title: uniqueName("Live Blocking Story"),
      description: "desc",
    });
    const blocked = await api.createStory(epic.id, {
      title: uniqueName("Live Blocked Story"),
      description: "desc",
    });

    const timelinePage = new RoadmapTimelinePage(page);

    try {
      await timelinePage.goto();
      await expect(timelinePage.markerByLabel(blocked.title)).toBeVisible();
      expect(await timelinePage.riskFor(blocked.title)).toBe("none");

      // Simulate a second session creating the dependency directly via the API — mirrors this
      // file's own "reflects a Story created out-of-band without a manual refresh" pattern (drive
      // state via API, assert the already-open page updates via STOMP without a reload).
      await api.createDependency({
        blockingItemType: "story",
        blockingItemId: blocking.id,
        blockedItemType: "story",
        blockedItemId: blocked.id,
      });

      await expect(
        timelinePage.markerByLabel(blocked.title).getByTestId("roadmap-timeline-story-blocked-badge"),
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test.describe("item detail on hover/click", () => {
    test("hovering a Story marker shows a preview with its status and parent Epic, no click needed", async ({
      api,
      workerRepo,
      page,
    }) => {
      const epic = await api.createEpic({
        title: uniqueName("E2E Timeline Detail Epic"),
        description: "desc",
        softwareProjectId: workerRepo.gitRepo.id,
      });
      const story = await api.createStory(epic.id, {
        title: uniqueName("Timeline Detail Story"),
        description: "desc",
      });

      const timelinePage = new RoadmapTimelinePage(page);

      try {
        await timelinePage.goto();
        const marker = timelinePage.markerByLabel(story.title);
        await expect(marker).toBeVisible();

        await marker.hover();

        await expect(timelinePage.itemPreview).toBeVisible();
        await expect(timelinePage.itemPreview).toContainText(story.title);
        await expect(timelinePage.itemPreview).toContainText(epic.title);
        // The hover preview never opens the pinned panel — it's a client-only glance (Decision 3).
        await expect(timelinePage.detailPanel).toHaveCount(0);
      } finally {
        await api.deleteEpic(epic.id);
      }
    });

    test("clicking a blocked Story opens the pinned detail panel and lists its blocker", async ({
      api,
      workerRepo,
      page,
    }) => {
      const epic = await api.createEpic({
        title: uniqueName("E2E Timeline Detail Blocked Epic"),
        description: "desc",
        softwareProjectId: workerRepo.gitRepo.id,
      });
      const blocking = await api.createStory(epic.id, {
        title: uniqueName("Timeline Detail Blocking Story"),
        description: "desc",
      });
      const blocked = await api.createStory(epic.id, {
        title: uniqueName("Timeline Detail Blocked Story"),
        description: "desc",
      });
      await api.createDependency({
        blockingItemType: "story",
        blockingItemId: blocking.id,
        blockedItemType: "story",
        blockedItemId: blocked.id,
      });

      const timelinePage = new RoadmapTimelinePage(page);

      try {
        await timelinePage.goto();
        await timelinePage.markerByLabel(blocked.title).click();

        await expect(timelinePage.detailPanel).toBeVisible();
        await expect(timelinePage.detailTitle).toHaveText(blocked.title);
        await expect(timelinePage.detailParent).toContainText(epic.title);
        await expect(timelinePage.blockingChain).toBeVisible();
        await expect(timelinePage.blockingChain).toContainText(blocking.title);

        await timelinePage.detailClose.click();
        await expect(timelinePage.detailPanel).toHaveCount(0);
      } finally {
        await api.deleteEpic(epic.id);
      }
    });

    test("clicking a ready Story opens the panel with no blocker section", async ({ api, workerRepo, page }) => {
      const epic = await api.createEpic({
        title: uniqueName("E2E Timeline Detail Ready Epic"),
        description: "desc",
        softwareProjectId: workerRepo.gitRepo.id,
      });
      const story = await api.createStory(epic.id, {
        title: uniqueName("Timeline Detail Ready Story"),
        description: "desc",
      });

      const timelinePage = new RoadmapTimelinePage(page);

      try {
        await timelinePage.goto();
        await timelinePage.markerByLabel(story.title).click();

        await expect(timelinePage.detailPanel).toBeVisible();
        await expect(timelinePage.detailTitle).toHaveText(story.title);
        await expect(timelinePage.blockingChain).toHaveCount(0);
      } finally {
        await api.deleteEpic(epic.id);
      }
    });

    test("a deep link carrying a focused Story id opens the panel on load", async ({ api, workerRepo, page }) => {
      const epic = await api.createEpic({
        title: uniqueName("E2E Timeline Detail Deep Link Epic"),
        description: "desc",
        softwareProjectId: workerRepo.gitRepo.id,
      });
      const story = await api.createStory(epic.id, {
        title: uniqueName("Timeline Detail Deep Link Story"),
        description: "desc",
      });

      const timelinePage = new RoadmapTimelinePage(page);

      try {
        await page.goto(`/roadmap/timeline?epic=${epic.id}&story=${story.id}`);
        await expect(timelinePage.heading).toBeVisible({ timeout: 15_000 });

        await expect(timelinePage.detailPanel).toBeVisible();
        await expect(timelinePage.detailTitle).toHaveText(story.title);
      } finally {
        await api.deleteEpic(epic.id);
      }
    });
  });
});
