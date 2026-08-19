import { type Page, type Locator } from "@playwright/test";

/** The three ticket types the Roadmap header's dropdown offers. */
export type TicketType = "epic" | "story" | "task";

/** The view types offered per ticket type. Graph is not one — it is a separate action. */
export type RoadmapViewName = "list" | "board" | "timeline";

/**
 * Component object for `RoadmapViewControls`, the one navigation control every Roadmap surface
 * renders: a ticket-type dropdown, one button per view type that exists for that ticket type, and
 * the contextual Graph action.
 *
 * Composed into each Roadmap page object rather than duplicated across them — the same reason the
 * app has one component instead of a hand-rolled link row per page.
 */
export class RoadmapViewControls {
  readonly page: Page;

  readonly root: Locator;
  readonly ticketTypeSelect: Locator;
  /** The Graph action — a disabled `<button>` until an Epic is focused, a link once one is. */
  readonly graphAction: Locator;

  constructor(page: Page) {
    this.page = page;

    this.root = page.getByTestId("roadmap-view-controls");
    this.ticketTypeSelect = page.getByTestId("roadmap-level-select");
    this.graphAction = page.getByTestId("roadmap-graph-action");
  }

  /**
   * One view button. Only views that exist for the current ticket type are rendered at all, so
   * asserting `toHaveCount(0)` here is how a spec proves a view is genuinely unavailable rather
   * than merely disabled.
   */
  view(name: RoadmapViewName): Locator {
    return this.page.getByTestId(`roadmap-view-${name}`);
  }

  /**
   * Switches the ticket type. The dropdown is the shared Base UI `Select`, not a native
   * `<select>` — open it by clicking the trigger, then click the option, mirroring the pattern
   * other page objects use for the same component.
   */
  async selectTicketType(type: TicketType) {
    await this.ticketTypeSelect.click();
    await this.page.getByTestId(`roadmap-level-option-${type}`).click();
  }
}
