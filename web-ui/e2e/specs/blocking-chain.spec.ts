import { test, expect } from "../fixtures";

test.describe("Blocking Chain", () => {
  test("opening a blocked Task's detail panel shows a multi-hop chain", async ({
    roadmapGraphPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Blocking Chain Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, { title: "Blocking Chain Story", description: "desc" });
    const taskA = await api.createTask(story.id, { title: "Chain Root Task", description: "desc" });
    const taskB = await api.createTask(story.id, { title: "Chain Middle Task", description: "desc" });
    const taskC = await api.createTask(story.id, { title: "Chain Tail Task", description: "desc" });

    // taskC (tail) blocks taskB (middle), taskB blocks taskA (root) — a 3-hop
    // chain behind taskA, none of which are done, so all 3 hops appear.
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: taskB.id,
      blockedItemType: "task",
      blockedItemId: taskA.id,
    });
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: taskC.id,
      blockedItemType: "task",
      blockedItemId: taskB.id,
    });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(taskA.title);

      await expect(roadmapGraphPage.blockingChainSection).toBeVisible();
      await expect(roadmapGraphPage.blockingChainNodes).toHaveCount(2);
      await expect(roadmapGraphPage.blockingChainSection).toContainText(taskB.title);
      await expect(roadmapGraphPage.blockingChainSection).toContainText(taskC.title);
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("a Task blocked only directly shows a single-hop chain", async ({ roadmapGraphPage, api }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E Single Hop Chain Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, { title: "Single Hop Story", description: "desc" });
    const blockingTask = await api.createTask(story.id, { title: "Direct Blocker Task", description: "desc" });
    const blockedTask = await api.createTask(story.id, { title: "Directly Blocked Task", description: "desc" });

    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: blockingTask.id,
      blockedItemType: "task",
      blockedItemId: blockedTask.id,
    });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(blockedTask.title);

      await expect(roadmapGraphPage.blockingChainSection).toBeVisible();
      await expect(roadmapGraphPage.blockingChainNodes).toHaveCount(1);
      await expect(roadmapGraphPage.blockingChainSection).toContainText(blockingTask.title);
    } finally {
      await api.deleteEpic(epic.id);
    }
  });

  test("an unblocked item's panel shows no blocking chain section", async ({ roadmapGraphPage, api }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const epic = await api.createEpic({
      title: `E2E No Chain Epic ${Date.now()}`,
      description: "desc",
      softwareProjectId: repos.content[0].id,
    });
    const story = await api.createStory(epic.id, { title: "No Chain Story", description: "desc" });
    const task = await api.createTask(story.id, { title: "Unblocked Task", description: "desc" });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(task.title);

      await expect(roadmapGraphPage.blockingChainSection).not.toBeVisible();
    } finally {
      await api.deleteEpic(epic.id);
    }
  });
});
