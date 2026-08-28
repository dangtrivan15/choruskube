import { test, expect } from "../fixtures";
import { uniqueName, type TestApiClient } from "../helpers/api-client";

/**
 * Deletes the Epic, tolerating the one failure a concurrent Autopilot tick can produce.
 *
 * Every test in this file deliberately leaves an unblocked Task sitting in `backlog` — that Task
 * is the head of the chain under test, so it cannot be blocked or done. A tick from
 * `autopilot.spec.ts` starts READY backlog Tasks from the WHOLE board, not just its own worker's,
 * and a started descendant makes the Epic undeletable. The delete is cleanup, not an assertion,
 * so losing that race must not fail a test that already proved what it came to prove.
 *
 * Scoped to exactly that message: any other cleanup failure still surfaces. `uniqueName()` keeps
 * the leftover rows from colliding with a later run.
 */
async function deleteEpicToleratingAutopilotStart(api: TestApiClient, epicId: string): Promise<void> {
  await api.deleteEpic(epicId).catch((error: unknown) => {
    const undeletable =
      error instanceof Error &&
      error.message.includes("Can only delete an Epic while all of its Tasks are still in backlog");
    if (!undeletable) throw error;
  });
}

test.describe("Blocking Chain", () => {
  test("opening a blocked Task's detail panel shows a multi-hop chain", async ({
    roadmapGraphPage,
    api,
    workerRepo,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Blocking Chain Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, { title: "Blocking Chain Story", description: "desc" });
    const taskA = await api.createTask(story.id, { title: uniqueName("Chain Root Task"), description: "desc" });
    const taskB = await api.createTask(story.id, { title: uniqueName("Chain Middle Task"), description: "desc" });
    const taskC = await api.createTask(story.id, { title: uniqueName("Chain Tail Task"), description: "desc" });

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
      await deleteEpicToleratingAutopilotStart(api, epic.id);
    }
  });

  test("a Task blocked only directly shows a single-hop chain", async ({ roadmapGraphPage, api, workerRepo }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Single Hop Chain Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, { title: "Single Hop Story", description: "desc" });
    const blockingTask = await api.createTask(story.id, { title: uniqueName("Direct Blocker Task"), description: "desc" });
    const blockedTask = await api.createTask(story.id, { title: uniqueName("Directly Blocked Task"), description: "desc" });

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
      await deleteEpicToleratingAutopilotStart(api, epic.id);
    }
  });

  test("an unblocked item's panel shows no blocking chain section", async ({ roadmapGraphPage, api, workerRepo }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E No Chain Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, { title: "No Chain Story", description: "desc" });
    const task = await api.createTask(story.id, { title: uniqueName("Unblocked Task"), description: "desc" });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(task.title);

      await expect(roadmapGraphPage.blockingChainSection).not.toBeVisible();
    } finally {
      await deleteEpicToleratingAutopilotStart(api, epic.id);
    }
  });
});
