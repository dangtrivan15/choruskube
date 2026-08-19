import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";

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
  /** The Graph action — a disabled `<button>` until an Epic is focused (Decision 3). */
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

  /** Resolves true if `title`'s card shows up in `stage`'s column within `timeout`. */
  private async cardLandedIn(title: string, stage: EpicStage, timeout: number): Promise<boolean> {
    return this.column(stage)
      .getByText(title)
      .waitFor({ state: "visible", timeout })
      .then(() => true)
      .catch(() => false);
  }

  /**
   * Drags the Epic card titled `title` into the `targetStage` column.
   *
   * dnd-kit's `PointerSensor` only starts a drag after the pointer has moved
   * past its activation-distance threshold, so this issues a raw
   * mouse down → a few incremental moves → mouse up sequence rather than a
   * single jump (which some DnD libraries miss because they see no
   * intermediate `pointermove` events).
   *
   * A hand-rolled pointer sequence opts out of Playwright's auto-waiting: the
   * coordinates are frozen at `boundingBox()` time, and any `roadmap-items`
   * STOMP event in the window before `mouse.down()` invalidates `["epics"]`
   * and re-orders the column, so the press can land on a moved or replaced
   * node and no drop happens at all. On a shared stack another worker's Epic
   * write is enough to trigger that. Hence: re-measure immediately before
   * pressing, abandon the attempt if the card shifted, and retry the whole
   * gesture until the card lands.
   */
  async dragCardToColumn(title: string, targetStage: EpicStage, attempts = 3) {
    const card = this.cardByTitle(title);
    const targetColumn = this.column(targetStage);

    for (let attempt = 1; attempt <= attempts; attempt++) {
      await card.waitFor({ state: "visible" });

      const sourceBox = await card.boundingBox();
      const targetBox = await targetColumn.boundingBox();
      if (!sourceBox || !targetBox) continue;

      const startX = sourceBox.x + sourceBox.width / 2;
      const startY = sourceBox.y + sourceBox.height / 2;
      const endX = targetBox.x + targetBox.width / 2;
      const endY = targetBox.y + Math.min(40, targetBox.height / 2);

      await this.page.mouse.move(startX, startY);

      // The pointer is in position but nothing is pressed yet — the last point
      // at which a re-render is still recoverable.
      const settledBox = await card.boundingBox();
      if (
        !settledBox ||
        Math.abs(settledBox.x - sourceBox.x) > 2 ||
        Math.abs(settledBox.y - sourceBox.y) > 2
      ) {
        continue;
      }

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

      if (await this.cardLandedIn(title, targetStage, 5_000)) return;
    }

    throw new Error(
      `Card "${title}" never landed in the ${targetStage} column after ${attempts} drag attempts`,
    );
  }
}
