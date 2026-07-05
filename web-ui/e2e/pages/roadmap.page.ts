import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Roadmap page (/roadmap).
 */
export class RoadmapPage {
  readonly page: Page;

  // Page elements
  readonly heading: Locator;
  readonly newProposalButton: Locator;
  readonly proposalList: Locator;
  readonly proposalItems: Locator;

  // Detail panel
  readonly detailTitle: Locator;
  readonly detailDescription: Locator;
  readonly detailStatus: Locator;
  readonly editButton: Locator;
  readonly startButton: Locator;
  readonly deleteButton: Locator;
  readonly rollOutButton: Locator;

  // Create dialog
  readonly createDialogTitle: Locator;
  readonly createTitleInput: Locator;
  readonly createDescriptionInput: Locator;
  readonly createMotivationInput: Locator;
  readonly createSoftwareProjectSelect: Locator;
  readonly createSubmitButton: Locator;

  // Edit dialog
  readonly editTitleInput: Locator;
  readonly editDescriptionInput: Locator;
  readonly editSaveButton: Locator;

  // Confirmation dialogs
  readonly deleteConfirmButton: Locator;
  readonly startConfirmButton: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("roadmap-heading");
    this.newProposalButton = page.getByTestId("new-proposal-button");
    this.proposalList = page.getByTestId("proposal-list");
    this.proposalItems = page.getByTestId("proposal-item");

    // Detail panel
    this.detailTitle = page.getByTestId("proposal-detail-title");
    this.detailDescription = page.getByTestId("proposal-detail-description");
    this.detailStatus = page.getByTestId("proposal-detail-status");
    this.editButton = page.getByTestId("proposal-edit-button");
    this.startButton = page.getByTestId("proposal-start-button");
    this.deleteButton = page.getByTestId("proposal-delete-button");
    this.rollOutButton = page.getByTestId("proposal-rollout-button");

    // Create dialog
    this.createDialogTitle = page.getByText("New Feature Proposal");
    this.createTitleInput = page.getByTestId("create-proposal-title");
    this.createDescriptionInput = page.getByTestId("create-proposal-description");
    this.createMotivationInput = page.getByTestId("create-proposal-motivation");
    this.createSoftwareProjectSelect = page.getByTestId(
      "create-proposal-software-project-select",
    );
    this.createSubmitButton = page.getByTestId("create-proposal-submit");

    // Edit dialog
    this.editTitleInput = page.getByTestId("edit-proposal-title");
    this.editDescriptionInput = page.getByTestId("edit-proposal-description");
    this.editSaveButton = page.getByTestId("edit-proposal-save");

    // Confirmation dialogs
    this.deleteConfirmButton = page.getByTestId("delete-proposal-confirm");
    this.startConfirmButton = page.getByTestId("start-proposal-confirm");
  }

  async goto() {
    await this.page.goto("/roadmap");
    await expect(this.heading).toBeVisible();
  }

  async selectProposal(title: string) {
    const item = this.proposalItems.filter({ hasText: title });
    await item.click();
    await expect(this.detailTitle).toContainText(title);
  }

  async openCreateDialog() {
    await this.newProposalButton.click();
    await expect(this.createDialogTitle).toBeVisible();
  }

  async createProposal(
    title: string,
    description: string,
    projectName: string,
    motivation?: string,
  ) {
    await this.openCreateDialog();

    await this.createTitleInput.fill(title);
    await this.createDescriptionInput.fill(description);

    if (motivation) {
      await this.createMotivationInput.fill(motivation);
    }

    await this.selectSoftwareProjectInCreateDialog(projectName);

    await this.createSubmitButton.click();
    await expect(this.createDialogTitle).not.toBeVisible();
  }

  /**
   * Selects a SoftwareProject (a single git_repo or a repo_group) in the
   * Create Proposal dialog's grouped dropdown by visible name.
   */
  async selectSoftwareProjectInCreateDialog(projectName: string) {
    await this.createSoftwareProjectSelect.click();
    await this.page
      .getByRole("option", { name: new RegExp(projectName) })
      .click();
  }

  async deleteProposal(title: string) {
    await this.selectProposal(title);
    await this.deleteButton.click();
    await this.deleteConfirmButton.click();
  }
}
