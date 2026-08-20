import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";

export type TaskStatus = "backlog" | "in_progress" | "done";

/**
 * Page object for the Task Board (/roadmap/board/tasks) — a Kanban view of
 * Tasks grouped into Backlog / In Progress / Done columns by `status`.
 */
export class TaskBoardPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly board: Locator;
  readonly cards: Locator;

  /**
   * Shared Roadmap header control. The Epic and Story boards are no longer reachable from here by
   * their own links — they are the *same* view of a different ticket type, so they are reached by
   * switching the ticket type (`viewControls.selectTicketType`).
   */
  readonly viewControls: RoadmapViewControls;
  /** The Task list — this board's own list view, not the Epic list. */
  readonly listViewLink: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("task-board-heading");
    this.board = page.getByTestId("task-board");
    this.cards = page.getByTestId("task-board-card");

    this.viewControls = new RoadmapViewControls(page);
    this.listViewLink = this.viewControls.view("list");
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

    // Measured from a non-interactive part of the card, never the card box: `.click()`-style
    // centre coordinates can land on the title, which is a `<Link>` deliberately excluded from the
    // drag surface (it stops `pointerdown`), so a press there starts no drag at all.
    const grip = card.getByTestId("task-board-card-status");
    const sourceBox = await grip.boundingBox();
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
