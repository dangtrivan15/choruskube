import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

/**
 * E2E coverage for the rollup progress bar / at-risk feature layered on top of Milestones
 * (server side: `MilestoneResponse.progress`/`atRisk`/`atRiskItemCount`, drill-down via
 * `GET /milestones/{id}/at-risk-items`). Progress-bar rendering is exercised in isolation
 * (Vitest covers the actual bucket math); this spec drives the at-risk verdict end to end,
 * since that requires the server-computed "target date passed while incomplete" rule.
 */
test.describe("Milestone progress and at-risk", () => {
  test("an overdue incomplete Epic makes its Milestone at-risk, with a working drill-down", async ({
    milestonesPage,
    api,
    workerRepo,
  }) => {
    const milestoneName = uniqueName("E2E At-Risk Milestone");
    const pastDate = "2020-01-15";

    const milestone = await api.createMilestone({
      name: milestoneName,
      softwareProjectId: workerRepo.gitRepo.id,
      targetDate: pastDate,
    });
    const epic = await api.createEpic({
      title: uniqueName("E2E At-Risk Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    await api.assignEpicToMilestone(epic.id, milestone.id);
    await api.setEpicTargetDate(epic.id, pastDate);

    // The Milestone row shows a progress bar (even with zero descendant Tasks) and the
    // at-risk badge, since its own target date is overdue and the tagged Epic is incomplete.
    await milestonesPage.goto();
    const row = milestonesPage.item(milestoneName);
    await expect(row.getByTestId("milestone-progress-bar")).toBeVisible();
    await expect(row.getByTestId("milestone-at-risk-badge")).toBeVisible();

    // Expanding the drill-down lists the overdue Epic.
    await milestonesPage.toggleAtRiskDrilldown(milestoneName);
    await expect(
      milestonesPage.atRiskDrilldownItems.filter({ hasText: epic.title }),
    ).toBeVisible();

    // Clean up.
    await api.deleteEpic(epic.id);
    await api.deleteMilestone(milestone.id);
  });

  test("a Milestone with no overdue work shows no at-risk badge", async ({
    milestonesPage,
    api,
    workerRepo,
  }) => {
    const milestoneName = uniqueName("E2E Not-At-Risk Milestone");
    const futureDate = "2099-01-15";

    const milestone = await api.createMilestone({
      name: milestoneName,
      softwareProjectId: workerRepo.gitRepo.id,
      targetDate: futureDate,
    });
    const epic = await api.createEpic({
      title: uniqueName("E2E Not-At-Risk Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    await api.assignEpicToMilestone(epic.id, milestone.id);
    await api.setEpicTargetDate(epic.id, futureDate);

    await milestonesPage.goto();
    const row = milestonesPage.item(milestoneName);
    await expect(row.getByTestId("milestone-progress-bar")).toBeVisible();
    await expect(row.getByTestId("milestone-at-risk-badge")).toHaveCount(0);

    // Clean up.
    await api.deleteEpic(epic.id);
    await api.deleteMilestone(milestone.id);
  });

  test("the create/edit Milestone dialogs collect and persist a target date", async ({
    milestonesPage,
    api,
    workerRepo,
  }) => {
    const projectName = workerRepo.gitRepo.url
      .replace(/^https?:\/\/[^/]+\//, "")
      .replace(/\.git$/, "");
    const milestoneName = uniqueName("E2E Target Date Milestone");
    const targetDate = "2030-06-01";

    await milestonesPage.goto();
    await milestonesPage.createMilestone(milestoneName, projectName, undefined, targetDate);
    await expect(milestonesPage.item(milestoneName)).toBeVisible();

    const created = (await api.listMilestones(workerRepo.gitRepo.id)).content.find(
      (m) => m.name === milestoneName,
    );
    expect(created?.targetDate).toBe(targetDate);

    const updatedDate = "2030-09-01";
    await milestonesPage.openEditDialog(milestoneName);
    await milestonesPage.editTargetDateInput.fill(updatedDate);
    await milestonesPage.editSaveButton.click();
    await expect(milestonesPage.editSaveButton).not.toBeVisible();

    const updated = (await api.listMilestones(workerRepo.gitRepo.id)).content.find(
      (m) => m.name === milestoneName,
    );
    expect(updated?.targetDate).toBe(updatedDate);

    // Clean up.
    if (updated) await api.deleteMilestone(updated.id);
  });
});
