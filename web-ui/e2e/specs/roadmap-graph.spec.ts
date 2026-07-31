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

  test("a 3-task chain stays BLOCKED at the tail after the middle task completes but the root has not", async ({
    roadmapGraphPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Graph Chain Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, { title: "Chain Story", description: "desc" });
    const taskA = await api.createTask(story.id, { title: "Chain Task A (root)", description: "desc" });
    const taskB = await api.createTask(story.id, { title: "Chain Task B (middle)", description: "desc" });
    const taskC = await api.createTask(story.id, { title: "Chain Task C (tail)", description: "desc" });

    // A blocks B, B blocks C. taskA is never started below, so it's the only
    // undone item in the chain.
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: taskA.id,
      blockedItemType: "task",
      blockedItemId: taskB.id,
    });
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: taskB.id,
      blockedItemType: "task",
      blockedItemId: taskC.id,
    });

    // Get taskB to "done" without depending on its Task-triggered run (the
    // real "Feature Development" template, unlike the trivial e2e-linear-pipeline
    // used elsewhere in this suite) actually completing — that template requires
    // cloning taskB's git repo, which is unrelated to what this test verifies and
    // an unnecessary source of flakiness/slowness here. `completeTask` only
    // requires the Task's most recent linked run to be in a *terminal* state
    // (RunService#cancelRun sets that synchronously), not specifically
    // "completed" (mirrors run-lifecycle.spec.ts's "cancel button terminates a
    // running workflow" pattern). Wait for the run to actually reach "running"
    // first: the orchestrator's own async node-dispatch callback
    // (InternalRunService#updateRunStatus) unconditionally overwrites run
    // status with no terminal-state guard, so cancelling before that callback
    // lands races it and can get silently clobbered back to "running" —
    // waiting first means cancel is the last write. taskA stays untouched in
    // backlog throughout.
    const started = await api.startTask(taskB.id);
    expect(started.latestRunId).not.toBeNull();
    await api.waitForRunStatus(started.latestRunId!, ["running"], 15_000);
    await api.cancelRun(started.latestRunId!);
    await api.completeTask(taskB.id);

    await roadmapGraphPage.goto(epic.id);

    // This is the regression this feature exists to fix: taskB (taskC's
    // direct blocker) is now done, but taskA — further upstream — is not, so
    // taskC must still render BLOCKED rather than flipping to READY the
    // moment its own immediate blocker clears.
    const tailNode = roadmapGraphPage.nodeByLabel(taskC.title);
    await expect(tailNode.getByTestId("roadmap-graph-node-blocked-badge")).toBeVisible();

    // No cleanup: starting taskB has moved it out of "backlog", and
    // DefaultEpicService#delete refuses to delete an Epic with any started
    // descendant Task (see run-lifecycle.spec.ts's breadcrumb test) — the
    // Date.now() title keeps this fixture from colliding with other runs.
  });

  test("clicking an external blocker's link navigates to its owning Epic's detail page", async ({
    roadmapGraphPage,
    api,
    page,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const firstEpic = await api.createEpic({
      title: `E2E External Blocker Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const secondEpic = await api.createEpic({
      title: `E2E Owning Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const firstStory = await api.createStory(firstEpic.id, { title: "Blocked Story", description: "desc" });
    const blockedTask = await api.createTask(firstStory.id, {
      title: "Externally Blocked Task",
      description: "desc",
    });
    const secondStory = await api.createStory(secondEpic.id, { title: "Blocking Story", description: "desc" });
    const blockingTask = await api.createTask(secondStory.id, {
      title: "External Blocking Task",
      description: "desc",
    });

    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: blockingTask.id,
      blockedItemType: "task",
      blockedItemId: blockedTask.id,
    });

    try {
      await roadmapGraphPage.goto(firstEpic.id);
      await roadmapGraphPage.selectNode(blockedTask.title);

      await expect(roadmapGraphPage.externalBlockers).toBeVisible();
      await expect(roadmapGraphPage.externalBlockerBadges).toContainText(secondEpic.title);

      await roadmapGraphPage.externalBlockerLink(blockingTask.title).click();

      await expect(page).toHaveURL(`/roadmap/epics/${secondEpic.id}`);
      await expect(page.getByTestId("epic-detail-title")).toHaveText(secondEpic.title);
    } finally {
      await api.deleteEpic(firstEpic.id);
      await api.deleteEpic(secondEpic.id);
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
