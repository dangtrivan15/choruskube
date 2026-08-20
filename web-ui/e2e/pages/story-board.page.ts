import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";

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
   * Drags the Story card titled `title` into the `targetStage` column.
   *
   * dnd-kit's `PointerSensor` only starts a drag after the pointer has moved
   * past its activation-distance threshold, so this issues a raw
   * mouse down → a few incremental moves → mouse up sequence rather than a
   * single jump (which some DnD libraries miss because they see no
   * intermediate `pointermove` events). Mirrors TaskBoardPage/RoadmapBoardPage's
   * `dragCardToColumn`.
   */
  async dragCardToColumn(title: string, targetStage: StoryStage) {
    const card = this.cardByTitle(title);
    const targetColumn = this.column(targetStage);

    // Measured from a non-interactive part of the card, never the card box: `.click()`-style
    // centre coordinates can land on the title, which is a `<Link>` deliberately excluded from the
    // drag surface (it stops `pointerdown`), so a press there starts no drag at all.
    const grip = card.getByTestId("story-board-card-progress");
    const sourceBox = await grip.boundingBox();
    const targetBox = await targetColumn.boundingBox();
    if (!sourceBox || !targetBox) {
      throw new Error(`Could not measure drag source/target for "${title}" -> ${targetStage}`);
    }

    const startX = sourceBox.x + sourceBox.width / 2;
    const startY = sourceBox.y + sourceBox.height / 2;
    const endX = targetBox.x + targetBox.width / 2;
    const endY = targetBox.y + Math.min(40, targetBox.height / 2);

    await this.page.mouse.move(startX, startY);
    await this.page.mouse.down();
    await this.page.mouse.move(
      startX + (endX - startX) / 3,
      startY + (endY - startY) / 3,
      { steps: 5 },
    );
    await this.page.mouse.move(
      startX + ((endX - startX) * 2) / 3,
      startY + ((endY - startY) * 2) / 3,
      { steps: 5 },
    );
    await this.page.mouse.move(endX, endY, { steps: 5 });
    await this.page.mouse.up();
  }
}
