import { type Page, type Locator, expect } from "@playwright/test";

export type StoryStage = "backlog" | "in_progress" | "rolled_out";

/**
 * Page object for the Story Board (/roadmap/board/stories) — a Kanban view
 * of Stories grouped into Backlog / In Progress / Rolled Out columns by
 * `stage`. Mirrors TaskBoardPage's page object structure.
 */
export class StoryBoardPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly epicBoardLink: Locator;
  readonly taskBoardLink: Locator;
  readonly listViewLink: Locator;
  readonly board: Locator;
  readonly cards: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("story-board-heading");
    this.epicBoardLink = page.getByTestId("story-board-epic-board-link");
    this.taskBoardLink = page.getByTestId("story-board-task-board-link");
    this.listViewLink = page.getByTestId("story-board-list-view-link");
    this.board = page.getByTestId("story-board");
    this.cards = page.getByTestId("story-board-card");
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

    const sourceBox = await card.boundingBox();
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
