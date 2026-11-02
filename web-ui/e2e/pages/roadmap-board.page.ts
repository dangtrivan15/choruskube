import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";
import { dragBoardCard } from "../helpers/board-drag";

export type EpicStage = "backlog" | "in_progress" | "rolled_out";

/**
 * Page object for the Roadmap Board (/roadmap/board) — a Kanban view of
 * Epics grouped into Backlog / In Progress / Rolled Out columns by `stage`.
 */
export class RoadmapBoardPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly board: Locator;
  readonly cards: Locator;
  readonly readyToStartToggle: Locator;

  /** Shared Roadmap header control — ticket type, view types, and the Graph action. */
  readonly viewControls: RoadmapViewControls;
  readonly listViewLink: Locator;
  readonly timelineViewLink: Locator;
  /** The Graph action — a disabled `<button>` until an Epic is focused. */
  readonly graphAction: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("roadmap-board-heading");
    this.board = page.getByTestId("roadmap-board");
    this.cards = page.getByTestId("epic-board-card");
    this.readyToStartToggle = page.getByTestId("ready-to-start-toggle");

    this.viewControls = new RoadmapViewControls(page);
    this.listViewLink = this.viewControls.view("list");
    this.timelineViewLink = this.viewControls.view("timeline");
    this.graphAction = this.viewControls.graphAction;
  }

  async goto() {
    await this.page.goto("/roadmap/board");
    await expect(this.heading).toBeVisible();
    await expect(this.board).toBeVisible();
  }

  column(stage: EpicStage): Locator {
    return this.page.getByTestId(`board-column-${stage}`);
  }

  /**
   * Focuses a card (sets `?epic=`) without navigating. Clicks the progress line rather than the
   * card, because `click()` targets a locator's geometric centre and the card's title is a `Link`
   * to the Epic detail page — a centre click that lands on it would navigate away instead.
   */
  async focusCard(title: string) {
    await this.cardByTitle(title).getByTestId("epic-board-card-progress").click();
  }

  cardByTitle(title: string): Locator {
    return this.cards.filter({ hasText: title });
  }

  async expandCard(title: string) {
    const card = this.cardByTitle(title);
    await card.getByTestId("epic-board-card-expand").click();
  }

  storyRowsFor(title: string): Locator {
    return this.cardByTitle(title).getByTestId("epic-board-card-story");
  }

  /** Asserts the Epic card with `title` is currently rendered inside `stage`'s column. */
  async expectCardInColumn(title: string, stage: EpicStage) {
    await expect(this.column(stage).getByText(title)).toBeVisible();
  }

  /**
   * Drags the Epic card titled `title` into the `targetStage` column and waits for the server to
   * accept the move, so a following reload reads the new stage rather than racing the write.
   */
  async dragCardToColumn(title: string, targetStage: EpicStage) {
    const card = this.cardByTitle(title);
    const epicId = await card.getAttribute("data-epic-id");
    expect(epicId, `data-epic-id on "${title}"`).not.toBeNull();
    const response = await dragBoardCard({
      card,
      grip: card.getByTestId("epic-board-card-progress"),
      target: this.column(targetStage),
      isWrite: (r) =>
        r.request().method() === "PATCH" && r.url().endsWith(`/api/v1/epics/${epicId}/stage`),
    });
    expect(
      response.ok(),
      `PATCH stage of "${title}" -> ${targetStage}: ${response.status()}`,
    ).toBe(true);
  }
}
