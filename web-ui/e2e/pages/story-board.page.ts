import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";
import { dragBoardCard } from "../helpers/board-drag";

export type StoryStage = "backlog" | "in_progress" | "rolled_out";

/**
 * Page object for the Story Board (/roadmap/board/stories) — a Kanban view
 * of Stories grouped into Backlog / In Progress / Rolled Out columns by
 * `stage`. Mirrors TaskBoardPage's page object structure.
 */
export class StoryBoardPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly board: Locator;
  readonly cards: Locator;

  /**
   * Shared Roadmap header control. The Epic and Task boards are no longer reachable from here by
   * their own links — they are the *same* view of a different ticket type, so they are reached by
   * switching the ticket type (`viewControls.selectTicketType`).
   */
  readonly viewControls: RoadmapViewControls;
  /** The Story list — this board's own list view, not the Epic list. */
  readonly listViewLink: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("story-board-heading");
    this.board = page.getByTestId("story-board");
    this.cards = page.getByTestId("story-board-card");

    this.viewControls = new RoadmapViewControls(page);
    this.listViewLink = this.viewControls.view("list");
  }

  async goto() {
    await this.page.goto("/roadmap/board/stories");
    await expect(this.heading).toBeVisible();
    await expect(this.board).toBeVisible();
  }

  column(stage: StoryStage): Locator {
    return this.page.getByTestId(`board-column-${stage}`);
  }

  cardByTitle(title: string): Locator {
    return this.cards.filter({ hasText: title });
  }

  /** Asserts the Story card with `title` is currently rendered inside `stage`'s column. */
  async expectCardInColumn(title: string, stage: StoryStage) {
    await expect(this.column(stage).getByText(title)).toBeVisible();
  }

  /**
   * Drags the Story card titled `title` into the `targetStage` column and waits for the server to
   * accept the move, so a following reload reads the new stage rather than racing the write.
   */
  async dragCardToColumn(title: string, targetStage: StoryStage) {
    const card = this.cardByTitle(title);
    const storyId = await card.getAttribute("data-story-id");
    const response = await dragBoardCard({
      card,
      grip: card.getByTestId("story-board-card-progress"),
      target: this.column(targetStage),
      isWrite: (r) =>
        r.request().method() === "PATCH" && r.url().endsWith(`/api/v1/stories/${storyId}/stage`),
    });
    expect(
      response.ok(),
      `PATCH stage of "${title}" -> ${targetStage}: ${response.status()}`,
    ).toBe(true);
  }
}
