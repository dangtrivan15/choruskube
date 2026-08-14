import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Milestone management surface (`/roadmap/milestones`) — list, create,
 * rename, and delete Milestones (Decision 1 of the "Group Epics under a named Milestone /
 * Release" feature).
 */
export class MilestonesPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly newMilestoneButton: Locator;
  readonly milestoneList: Locator;
  readonly milestoneItems: Locator;

  // Create Milestone dialog
  readonly createDialogTitle: Locator;
  readonly createNameInput: Locator;
  readonly createDescriptionInput: Locator;
  readonly createSoftwareProjectSelect: Locator;
  readonly createSubmitButton: Locator;

  // Edit Milestone dialog
  readonly editNameInput: Locator;
  readonly editDescriptionInput: Locator;
  readonly editSaveButton: Locator;

  // Delete confirmation dialog
  readonly deleteConfirmButton: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("milestones-heading");
    this.newMilestoneButton = page.getByTestId("new-milestone-button");
    this.milestoneList = page.getByTestId("milestone-list");
    this.milestoneItems = page.getByTestId("milestone-item");

    // Scoped to the dialog heading (role=heading) — mirrors RoadmapPage's rationale for its
    // own createDialogTitle: a plain text locator risks matching the "New Milestone" button too.
    this.createDialogTitle = page.getByRole("heading", { name: "New Milestone" });
    this.createNameInput = page.getByTestId("create-milestone-name");
    this.createDescriptionInput = page.getByTestId("create-milestone-description");
    this.createSoftwareProjectSelect = page.getByTestId(
      "create-milestone-software-project-select",
    );
    this.createSubmitButton = page.getByTestId("create-milestone-submit");

    this.editNameInput = page.getByTestId("edit-milestone-name");
    this.editDescriptionInput = page.getByTestId("edit-milestone-description");
    this.editSaveButton = page.getByTestId("edit-milestone-save");

    this.deleteConfirmButton = page.getByTestId("delete-milestone-confirm");
  }

  async goto() {
    await this.page.goto("/roadmap/milestones");
    await expect(this.heading).toBeVisible();
  }

  /** The row for the Milestone titled `name`. */
  item(name: string): Locator {
    return this.milestoneItems.filter({ hasText: name });
  }

  async openCreateDialog() {
    await this.newMilestoneButton.click();
    await expect(this.createDialogTitle).toBeVisible();
  }

  /**
   * Selects a SoftwareProject in the Create Milestone dialog's dropdown by visible name.
   * Uses an exact match — see `RoadmapPage.selectSoftwareProjectInCreateDialog` for why.
   */
  async selectSoftwareProjectInCreateDialog(projectName: string) {
    await this.createSoftwareProjectSelect.click();
    await this.page.getByRole("option", { name: projectName, exact: true }).click();
  }

  async createMilestone(name: string, projectName: string, description?: string) {
    await this.openCreateDialog();
    await this.createNameInput.fill(name);
    if (description) {
      await this.createDescriptionInput.fill(description);
    }
    await this.selectSoftwareProjectInCreateDialog(projectName);
    await this.createSubmitButton.click();
    await expect(this.createDialogTitle).not.toBeVisible();
  }

  async openEditDialog(name: string) {
    await this.item(name).getByTestId("milestone-edit-button").click();
    await expect(this.editNameInput).toHaveValue(name);
  }

  async renameMilestone(name: string, newName: string) {
    await this.openEditDialog(name);
    await this.editNameInput.fill(newName);
    await this.editSaveButton.click();
    await expect(this.editSaveButton).not.toBeVisible();
  }

  async deleteMilestone(name: string) {
    await this.item(name).getByTestId("milestone-delete-button").click();
    await this.deleteConfirmButton.click();
    await expect(this.deleteConfirmButton).not.toBeVisible();
  }
}
