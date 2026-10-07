import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Epic dependencies on the Epic page", () => {
  test("a blocker added on one Epic shows on the other's Blocks list and can be removed there", async ({
    epicDetailPage,
    api,
    workerRepo,
  }) => {
    const epicA = await api.createEpic({
      title: uniqueName("E2E Epic Dep A"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const epicB = await api.createEpic({
      title: uniqueName("E2E Epic Dep B"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });

    try {
      await epicDetailPage.goto(epicA.id);
      await epicDetailPage.addBlockedBy(epicB.title);
      const blockerRow = epicDetailPage.row(epicDetailPage.blockedByList, epicB.title);
      await expect(blockerRow).toBeVisible();

      // The row links to the other Epic, whose own page shows the same edge from its side.
      await blockerRow.getByRole("link", { name: epicB.title }).click();
      await expect(epicDetailPage.title).toHaveText(epicB.title);
      const blockedRow = epicDetailPage.row(epicDetailPage.blocksList, epicA.title);
      await expect(blockedRow).toBeVisible();

      await epicDetailPage.remove(epicDetailPage.blocksList, epicA.title);
      await expect(blockedRow).toHaveCount(0);
      expect(await api.listEpicDependencies(epicA.id)).toEqual([]);
    } finally {
      await api.deleteEpic(epicA.id);
      await api.deleteEpic(epicB.id);
    }
  });

  test("an edge that would close a cycle is rejected with the backend's message", async ({
    epicDetailPage,
    api,
    workerRepo,
  }) => {
    const epicA = await api.createEpic({
      title: uniqueName("E2E Epic Cycle A"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const epicB = await api.createEpic({
      title: uniqueName("E2E Epic Cycle B"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    await api.createDependency({
      blockingItemType: "epic",
      blockingItemId: epicB.id,
      blockedItemType: "epic",
      blockedItemId: epicA.id,
    });

    try {
      await epicDetailPage.goto(epicA.id);
      await expect(epicDetailPage.row(epicDetailPage.blockedByList, epicB.title)).toBeVisible();

      // B already blocks A, so A blocking B closes a loop.
      await epicDetailPage.addBlocks(epicB.title);

      const cycleToast = epicDetailPage.page
        .locator('[data-sonner-toast][data-type="warning"]')
        .filter({ hasText: "would close a cycle" });
      await expect(cycleToast).toBeVisible();
      await expect(epicDetailPage.row(epicDetailPage.blocksList, epicB.title)).toHaveCount(0);
      const edges = await api.listEpicDependencies(epicA.id);
      expect(edges.map((e) => [e.direction, e.itemId])).toEqual([["BLOCKED", epicB.id]]);
    } finally {
      await api.deleteEpic(epicA.id);
      await api.deleteEpic(epicB.id);
    }
  });
});
