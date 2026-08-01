import { type Page, type Locator, expect } from "@playwright/test";

export type TaskStatus = "backlog" | "in_progress" | "done";

/**
 * Page object for the Task Board (/roadmap/board/tasks) — a Kanban view of
 * Tasks grouped into Backlog / In Progress / Done columns by `status`.
 */
export class TaskBoardPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly epicBoardLink: Locator;
  readonly listViewLink: Locator;
  readonly board: Locator;
  readonly cards: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("task-board-heading");
    this.epicBoardLink = page.getByTestId("task-board-epic-board-link");
    this.listViewLink = page.getByTestId("task-board-list-view-link");
    this.board = page.getByTestId("task-board");
    this.cards = page.getByTestId("task-board-card");
  }

  async goto() {
    await this.page.goto("/roadmap/board/tasks");
    await expect(this.heading).toBeVisible();
    await expect(this.board).toBeVisible();
  }

  column(status: TaskStatus): Locator {
    return this.page.getByTestId(`board-column-${status}`);
  }

  cardByTitle(title: string): Locator {
    return this.cards.filter({ hasText: title });
  }

  /** Asserts the Task card with `title` is currently rendered inside `status`'s column. */
  async expectCardInColumn(title: string, status: TaskStatus) {
    await expect(this.column(status).getByText(title)).toBeVisible();
  }

  /**
   * Drags the Task card titled `title` into the `targetStatus` column.
   *
   * dnd-kit's `PointerSensor` only starts a drag after the pointer has moved
   * past its activation-distance threshold, so this issues a raw
   * mouse down → a few incremental moves → mouse up sequence rather than a
   * single jump (which some DnD libraries miss because they see no
   * intermediate `pointermove` events). Mirrors RoadmapBoardPage's
   * `dragCardToColumn`.
   */
  async dragCardToColumn(title: string, targetStatus: TaskStatus) {
    const card = this.cardByTitle(title);
    const targetColumn = this.column(targetStatus);

    const sourceBox = await card.boundingBox();
    const targetBox = await targetColumn.boundingBox();
    if (!sourceBox || !targetBox) {
      throw new Error(`Could not measure drag source/target for "${title}" -> ${targetStatus}`);
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
