import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Roadmap Provisioner's editable candidate breakdown,
 * rendered inside a `GateCard` on the Approvals page (/approvals) whenever
 * `PendingGateResponse.candidateBreakdown` is non-null (see
 * `RoadmapCandidateBreakdown.tsx`). Builds on the same `/approvals` surface
 * exercised by `ApprovalsPage` (approvals.page.ts) — this page object adds
 * locators/actions scoped to the breakdown editor itself.
 */
export class RoadmapCandidateGatePage {
  readonly page: Page;

  readonly heading: Locator;
  readonly gateCards: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("approvals-heading");
    this.gateCards = page.getByTestId("gate-card");
  }

  async goto() {
    await this.page.goto("/approvals");
    await expect(this.heading).toBeVisible();
  }

  /** Scopes to the single gate card matching the given run name or node label. */
  card(nodeLabelOrRunName: string): Locator {
    return this.gateCards.filter({ hasText: nodeLabelOrRunName });
  }

  /**
   * Waits for the gate card to appear, reloading in the meantime — the
   * `/pending-gates` projection can lag a beat behind the node-status REST
   * read (same pattern used throughout human-gates.spec.ts).
   */
  async waitForGateCard(nodeLabelOrRunName: string): Promise<Locator> {
    const card = this.card(nodeLabelOrRunName);
    await expect(async () => {
      if ((await card.count()) === 0) {
        await this.page.reload();
        await expect(this.heading).toBeVisible();
      }
      await expect(card).toBeVisible();
    }).toPass({ timeout: 30_000 });
    return card;
  }

  breakdown(card: Locator): Locator {
    return card.getByTestId("roadmap-candidate-breakdown");
  }

  epicTitleInput(card: Locator, epicIdx = 0): Locator {
    return card.getByTestId(`candidate-epic-title-${epicIdx}`);
  }

  epicDescriptionInput(card: Locator, epicIdx = 0): Locator {
    return card.getByTestId(`candidate-epic-description-${epicIdx}`);
  }

  async editEpicTitle(card: Locator, newTitle: string, epicIdx = 0) {
    await this.epicTitleInput(card, epicIdx).fill(newTitle);
  }

  async removeEpic(card: Locator, epicIdx = 0) {
    await card.getByTestId(`candidate-epic-remove-${epicIdx}`).click();
  }

  async addStory(card: Locator, epicIdx = 0) {
    await card.getByTestId(`candidate-add-story-${epicIdx}`).click();
  }

  async removeStory(card: Locator, epicIdx = 0, storyIdx = 0) {
    await card.getByTestId(`candidate-story-remove-${epicIdx}-${storyIdx}`).click();
  }

  async addTask(card: Locator, epicIdx = 0, storyIdx = 0) {
    await card.getByTestId(`candidate-add-task-${epicIdx}-${storyIdx}`).click();
  }

  async approve(card: Locator, feedback?: string) {
    if (feedback) {
      await card.getByTestId("gate-card-feedback").fill(feedback);
    }
    await card.getByTestId("gate-card-approve-button").click();
  }

  async reject(card: Locator, feedback: string) {
    await card.getByTestId("gate-card-feedback").fill(feedback);
    await card.getByTestId("gate-card-reject-button").click();
  }
}
