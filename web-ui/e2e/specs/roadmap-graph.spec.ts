import { test, expect } from "../fixtures";

test.describe("Roadmap Graph View", () => {
  test("opens the graph view for an Epic and shows the Story/Task tree shape", async ({
    roadmapGraphPage,
    roadmapPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Graph Epic ${Date.now()}`,
      description: "Epic for graph view e2e",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, {
      title: "Graph Story",
      description: "desc",
    });
    const task1 = await api.createTask(story.id, { title: "Graph Task One", description: "desc" });
    const task2 = await api.createTask(story.id, { title: "Graph Task Two", description: "desc" });

    try {
      // Reachable from the Epic list, not just by direct URL.
      await roadmapPage.goto();
      await expect(roadmapPage.page.getByTestId("epic-graph-link").first()).toBeVisible();

      await roadmapGraphPage.goto(epic.id);
      await expect(roadmapGraphPage.nodeByLabel(epic.title)).toBeVisible();
      await expect(roadmapGraphPage.nodeByLabel(story.title)).toBeVisible();
      await expect(roadmapGraphPage.nodeByLabel(task1.title)).toBeVisible();
      await expect(roadmapGraphPage.nodeByLabel(task2.title)).toBeVisible();
      await expect(roadmapGraphPage.nodes).toHaveCount(4);
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("clicking a Task node opens the detail panel with its run history", async ({
    roadmapGraphPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Graph Detail Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, { title: "Detail Story", description: "desc" });
    const task = await api.createTask(story.id, { title: "Detail Task", description: "desc" });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(task.title);

      await expect(roadmapGraphPage.detailStatus).toBeVisible();
      await expect(roadmapGraphPage.detailDescription).toBeVisible();
      await expect(roadmapGraphPage.taskRunHistoryList).toBeVisible();
      await expect(roadmapGraphPage.page.getByText(/No runs yet/i)).toBeVisible();

      await roadmapGraphPage.detailClose.click();
      await expect(roadmapGraphPage.detailPanel).not.toBeVisible();
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("creates a blocking dependency via the UI and draws a distinct dependency edge", async ({
    roadmapGraphPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Graph Dependency Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, { title: "Dependency Story", description: "desc" });
    const blockingTask = await api.createTask(story.id, { title: "Blocking Task", description: "desc" });
    const blockedTask = await api.createTask(story.id, { title: "Blocked Task", description: "desc" });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(blockedTask.title);
      await roadmapGraphPage.addBlocker(blockingTask.title);

      await expect(roadmapGraphPage.blockingDependencies).toBeVisible();
      await expect(roadmapGraphPage.blockingDependencyBadges).toContainText(blockingTask.title);

      // The new blocking edge renders as its own React Flow edge, distinct
      // from the Epic->Story/Story->Task hierarchy edges (see
      // roadmapDependencyEdgeId's "dep:" prefix in src/lib/elkLayout.ts).
      const dependencyEdge = roadmapGraphPage.page.locator('.react-flow__edge[data-id^="dep:"]');
      await expect(dependencyEdge).toHaveCount(1);

      // Clean up the edge itself via the remove button, then confirm it's gone.
      await roadmapGraphPage.blockingDependencyRemoveButtons.first().click();
      await expect(roadmapGraphPage.page.getByText("No blocking dependencies.")).toBeVisible();
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("reflects a dependency created out-of-band without a manual refresh", async ({
    roadmapGraphPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Graph Live Update Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, { title: "Live Update Story", description: "desc" });
    const blockingTask = await api.createTask(story.id, { title: "Live Blocking Task", description: "desc" });
    const blockedTask = await api.createTask(story.id, { title: "Live Blocked Task", description: "desc" });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(blockedTask.title);
      await expect(roadmapGraphPage.page.getByText("No blocking dependencies.")).toBeVisible();

      // Simulate a second session creating the dependency directly via the API
      // (mirrors real-time-updates.spec.ts's "drive state via API, assert the
      // already-open page updates via STOMP without a reload" pattern).
      await api.createDependency({
        blockingItemType: "task",
        blockingItemId: blockingTask.id,
        blockedItemType: "task",
        blockedItemId: blockedTask.id,
      });

      await expect(roadmapGraphPage.blockingDependencyBadges).toContainText(blockingTask.title, {
        timeout: 15_000,
      });
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("collapsing and expanding a large Story branch shows/hides its Tasks", async ({
    roadmapGraphPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Graph Collapse Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const bigStory = await api.createStory(epic.id, { title: "Big Story", description: "desc" });
    // One more Task than RoadmapGraph's AUTO_COLLAPSE_TASK_THRESHOLD (8), so
    // this branch starts collapsed by default.
    const tasks = [];
    for (let i = 0; i < 9; i++) {
      tasks.push(await api.createTask(bigStory.id, { title: `Collapse Task ${i}`, description: "desc" }));
    }

    try {
      await roadmapGraphPage.goto(epic.id);

      expect(await roadmapGraphPage.isCollapsed(bigStory.title)).toBe(true);
      await expect(roadmapGraphPage.nodeByLabel(tasks[0].title)).not.toBeVisible();

      await roadmapGraphPage.toggleCollapse(bigStory.title);
      expect(await roadmapGraphPage.isCollapsed(bigStory.title)).toBe(false);
      await expect(roadmapGraphPage.nodeByLabel(tasks[0].title)).toBeVisible();

      await roadmapGraphPage.toggleCollapse(bigStory.title);
      expect(await roadmapGraphPage.isCollapsed(bigStory.title)).toBe(true);
      await expect(roadmapGraphPage.nodeByLabel(tasks[0].title)).not.toBeVisible();
    } finally {
      await api.deleteEpic(epic.id);
    }
  });
});
