import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Approvals page (/approvals).
 */
export class ApprovalsPage {
  readonly page: Page;

  // Page elements
  readonly heading: Locator;
  readonly pendingBadge: Locator;
  readonly gateCards: Locator;
  readonly emptyState: Locator;
  readonly artifactList: Locator;
  readonly artifactListItems: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("approvals-heading");
    this.pendingBadge = page.getByTestId("approvals-pending-badge");
    this.gateCards = page.getByTestId("gate-card");
    this.emptyState = page.getByText("No pending approvals");
    this.artifactList = page.getByTestId("artifact-list");
    this.artifactListItems = page.getByTestId("artifact-list-items").locator("li");
  }

  async goto() {
    await this.page.goto("/approvals");
    await expect(this.heading).toBeVisible();
  }

  async waitForGateCards() {
    await expect(
      this.gateCards.first().or(this.emptyState),
    ).toBeVisible({ timeout: 15_000 });
  }

  async getGateCount(): Promise<number> {
    return this.gateCards.count();
  }

  async approveGate(nodeLabel: string, feedback?: string) {
    const card = this.gateCards.filter({ hasText: nodeLabel });
    if (feedback) {
      await card.getByTestId("gate-card-feedback").fill(feedback);
    }
    await card.getByTestId("gate-card-approve-button").click();
  }

  async rejectGate(nodeLabel: string, feedback: string) {
    const card = this.gateCards.filter({ hasText: nodeLabel });
    await card.getByTestId("gate-card-feedback").fill(feedback);
    await card.getByTestId("gate-card-reject-button").click();
  }

  /**
   * v23 spec gate: re-run the upstream reviewer with typed guidance.
   * Requires feedback per the same policy as legacy reject.
   */
  async rereviewGate(nodeLabel: string, feedback: string) {
    const card = this.gateCards.filter({ hasText: nodeLabel });
    await card.getByTestId("gate-card-feedback").fill(feedback);
    await card.getByTestId("gate-card-rereview-button").click();
  }

  /**
   * v23 spec gate: send back for full re-author. Requires feedback.
   */
  async redraftGate(nodeLabel: string, feedback: string) {
    const card = this.gateCards.filter({ hasText: nodeLabel });
    await card.getByTestId("gate-card-feedback").fill(feedback);
    await card.getByTestId("gate-card-redraft-button").click();
  }

  async expectGateVisible(nodeLabel: string) {
    await expect(this.gateCards.filter({ hasText: nodeLabel })).toBeVisible();
  }

  async expectNoGates() {
    await expect(this.emptyState).toBeVisible();
  }
}
