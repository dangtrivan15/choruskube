import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

/**
 * E2E coverage for grouping Epics under a named Milestone / Release:
 * create a Milestone, tag Epics with it, see the badge/filter on the Roadmap list, rename it
 * (badge text updates), and delete it (Epics remain, now untagged).
 */
test.describe("Roadmap Milestones", () => {
  test("create a Milestone, tag an Epic, filter the list, rename, then delete un-tags it", async ({
    roadmapPage,
    milestonesPage,
    api,
    workerRepo,
  }) => {
    const projectName = workerRepo.gitRepo.url
      .replace(/^https?:\/\/[^/]+\//, "")
      .replace(/\.git$/, "");
    const milestoneName = uniqueName("E2E Milestone");
    const renamedName = uniqueName("E2E Milestone Renamed");

    // Two Epics created up front via API — the flow under test is Milestone management and
    // tagging, not Epic creation (covered by roadmap.spec.ts already).
    const taggedEpic = await api.createEpic({
      title: uniqueName("E2E Milestone Tagged Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const untaggedEpic = await api.createEpic({
      title: uniqueName("E2E Milestone Untagged Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });

    // 1. Create a Milestone via the management page.
    await milestonesPage.goto();
    await milestonesPage.createMilestone(milestoneName, projectName, "The Q3 release");
    await expect(milestonesPage.item(milestoneName)).toBeVisible();

    // 2. Tag the first Epic with it via the Epic detail page's inline selector
    // (Authorized require="canOperate" — visible by default with auth disabled in E2E mode).
    await roadmapPage.page.goto(`/roadmap/epics/${taggedEpic.id}`);
    await expect(roadmapPage.epicDetailMilestoneBadge).not.toBeVisible();
    await roadmapPage.selectMilestone(roadmapPage.epicDetailMilestoneSelect, milestoneName);
    await expect(roadmapPage.epicDetailMilestoneBadge).toHaveText(milestoneName);

    // 3. The Roadmap list surfaces the badge on the tagged Epic only.
    await roadmapPage.goto();
    await expect(roadmapPage.epicItemMilestoneBadge(taggedEpic.title)).toHaveText(milestoneName);
    await expect(roadmapPage.epicItemMilestoneBadge(untaggedEpic.title)).not.toBeVisible();

    // 4. Filtering to that Milestone narrows the list to the tagged Epic; "All" restores it.
    await roadmapPage.filterByMilestone(milestoneName);
    await expect(roadmapPage.epicItems.filter({ hasText: taggedEpic.title })).toBeVisible();
    await expect(roadmapPage.epicItems.filter({ hasText: untaggedEpic.title })).toHaveCount(0);
    await roadmapPage.filterByMilestone("all");
    await expect(roadmapPage.epicItems.filter({ hasText: untaggedEpic.title })).toBeVisible();

    // 5. Renaming the Milestone is reflected on the Epic's badge.
    await milestonesPage.goto();
    await milestonesPage.renameMilestone(milestoneName, renamedName);
    await expect(milestonesPage.item(renamedName)).toBeVisible();
    await expect(milestonesPage.item(milestoneName)).toHaveCount(0);

    await roadmapPage.goto();
    await expect(roadmapPage.epicItemMilestoneBadge(taggedEpic.title)).toHaveText(renamedName);

    // 6. Deleting the Milestone un-tags the Epic — the Epic itself remains
    // (ON DELETE SET NULL, not a cascade delete).
    await milestonesPage.goto();
    await milestonesPage.deleteMilestone(renamedName);
    await expect(milestonesPage.item(renamedName)).toHaveCount(0);

    await roadmapPage.goto();
    await expect(roadmapPage.epicItems.filter({ hasText: taggedEpic.title })).toBeVisible();
    await expect(roadmapPage.epicItemMilestoneBadge(taggedEpic.title)).not.toBeVisible();

    // Clean up (the Milestone is already deleted above).
    await api.deleteEpic(taggedEpic.id);
    await api.deleteEpic(untaggedEpic.id);
  });

  test("assigns and clears a Milestone from the Create/Edit Epic dialogs", async ({
    roadmapPage,
    api,
    workerRepo,
  }) => {
    const projectName = workerRepo.gitRepo.url
      .replace(/^https?:\/\/[^/]+\//, "")
      .replace(/\.git$/, "");
    const milestoneName = uniqueName("E2E Milestone Dialog");
    const milestone = await api.createMilestone({
      name: milestoneName,
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const epicTitle = uniqueName("E2E Milestone Dialog Epic");

    // Create an Epic via the UI, choosing the Milestone in the same dialog — this goes through
    // a PATCH follow-up after the POST completes (EpicRequest carries no milestoneId field).
    await roadmapPage.goto();
    await roadmapPage.openCreateDialog();
    await roadmapPage.createTitleInput.fill(epicTitle);
    await roadmapPage.createDescriptionInput.fill("Created with a Milestone pre-selected");
    await roadmapPage.selectSoftwareProjectInCreateDialog(projectName);
    await roadmapPage.selectMilestone(roadmapPage.createMilestoneSelect, milestoneName);
    await roadmapPage.createSubmitButton.click();
    await expect(roadmapPage.createDialogTitle).not.toBeVisible();

    await roadmapPage.goto();
    await expect(roadmapPage.epicItemMilestoneBadge(epicTitle)).toHaveText(milestoneName);

    // Clear the assignment via the Edit Epic dialog's "None" option.
    await roadmapPage.openEpic(epicTitle);
    await roadmapPage.epicEditButton.click();
    // Scope to the value slot, not the whole trigger button: Base UI's Select renders a
    // hidden decorative chevron as a sibling text node inside the trigger, which a bare
    // toHaveText on the trigger itself would sweep into the comparison and fail against.
    await expect(
      roadmapPage.editMilestoneSelect.locator("[data-slot='select-value']"),
    ).toHaveText(milestoneName);
    await roadmapPage.selectMilestone(roadmapPage.editMilestoneSelect, "None");
    await roadmapPage.editSaveButton.click();
    await expect(roadmapPage.epicDetailMilestoneBadge).not.toBeVisible();

    // Clean up.
    const created = (await api.listEpics()).content.find((e) => e.title === epicTitle);
    if (created) await api.deleteEpic(created.id);
    await api.deleteMilestone(milestone.id);
  });
});
