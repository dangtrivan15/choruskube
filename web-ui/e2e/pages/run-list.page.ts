import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Runs list page (/runs).
 */
export class RunListPage {
  readonly page: Page;

  // Page elements
  readonly heading: Locator;
  readonly statusFilter: Locator;
  readonly startRunButton: Locator;
  readonly runTable: Locator;
  readonly runRows: Locator;
  readonly emptyState: Locator;

  // Start Run dialog
  readonly templateSelect: Locator;
  readonly runNameInput: Locator;
  readonly startButton: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("run-list-heading");
    this.statusFilter = page.getByTestId("run-list-status-filter");
    this.startRunButton = page.getByTestId("start-run-button");
    this.runTable = page.getByTestId("run-list-table");
    this.runRows = page.getByTestId("run-row");
    this.emptyState = page.getByText("No runs found.");

    // Start Run dialog elements
    this.templateSelect = page.getByTestId("start-run-template-select");
    this.runNameInput = page.getByTestId("start-run-name-input");
    this.startButton = page.getByTestId("start-run-submit");
  }

  async goto() {
    await this.page.goto("/runs");
    await expect(this.heading).toBeVisible();
  }

  async waitForTableLoad() {
    // Wait for either rows or empty state to appear
    await expect(
      this.runRows.first().or(this.emptyState),
    ).toBeVisible({ timeout: 15_000 });
  }

  async getRunCount(): Promise<number> {
    return this.runRows.count();
  }

  async clickRun(index: number) {
    const row = this.runRows.nth(index);
    await row.click();
    await this.page.waitForURL("**/runs/*");
  }

  async clickRunById(runId: string) {
    const row = this.page.locator(`[data-run-id="${runId}"]`);
    await row.click();
    await this.page.waitForURL(`**/runs/${runId}`);
  }

  async filterByStatus(status: string) {
    await this.statusFilter.click();
    await this.page.getByRole("option", { name: status }).click();
  }

  async openStartRunDialog() {
    await this.startRunButton.click();
    await expect(this.page.getByText("Start a New Run")).toBeVisible();
  }

  async startRun(templateName: string, runName?: string) {
    await this.openStartRunDialog();

    // Select template
    await this.templateSelect.click();
    await this.page.getByRole("option", { name: new RegExp(templateName) }).click();

    // Set run name if provided
    if (runName) {
      await this.runNameInput.clear();
      await this.runNameInput.fill(runName);
    }

    // Click start
    await this.startButton.click();

    // Wait for dialog to close
    await expect(this.page.getByText("Start a New Run")).not.toBeVisible();
  }
}
