import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";
import { dragBoardCard } from "../helpers/board-drag";

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
   * Drags the Task card titled `title` into the `targetStatus` column and waits for the server to
   * accept the move, so a following reload reads the new status rather than racing the write.
   */
  async dragCardToColumn(title: string, targetStatus: TaskStatus) {
    const card = this.cardByTitle(title);
    const taskId = await card.getAttribute("data-task-id");
    expect(taskId, `data-task-id on "${title}"`).not.toBeNull();
    const response = await dragBoardCard({
      card,
      grip: card.getByTestId("task-board-card-status"),
      target: this.column(targetStatus),
      isWrite: (r) =>
        r.request().method() === "PATCH" && r.url().endsWith(`/api/v1/tasks/${taskId}/status`),
    });
    expect(
      response.ok(),
      `PATCH status of "${title}" -> ${targetStatus}: ${response.status()}`,
    ).toBe(true);
  }
}
