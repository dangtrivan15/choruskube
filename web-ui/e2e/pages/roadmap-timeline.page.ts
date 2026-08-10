import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Roadmap Timeline View (/roadmap/timeline) — Epics as horizontal swimlanes
 * with their Stories plotted along a shared time axis, rendered with @xyflow/react (no ELK — a
 * hand-rolled time-scale layout, see src/lib/timelineLayout.ts).
 */
export class RoadmapTimelinePage {
  readonly page: Page;

  readonly heading: Locator;
  readonly backToRoadmapLink: Locator;
  readonly container: Locator;
  readonly lanes: Locator;
  readonly markers: Locator;
  readonly emptyState: Locator;

  /** The switcher's Graph entry — a disabled `<button>` until an Epic is focused (Decision 3). */
  readonly viewSwitcherGraphEntry: Locator;
  readonly viewSwitcherBoardLink: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("roadmap-timeline-heading");
    this.backToRoadmapLink = page.getByRole("link", { name: "Back to Roadmap" });
    this.container = page.getByTestId("roadmap-timeline-container");
    this.lanes = page.getByTestId("roadmap-timeline-epic-lane");
    this.markers = page.getByTestId("roadmap-timeline-story-node");
    this.emptyState = page.getByTestId("roadmap-timeline-empty");

    this.viewSwitcherGraphEntry = page.getByTestId("roadmap-view-switcher-graph");
    this.viewSwitcherBoardLink = page.getByTestId("roadmap-view-switcher-board");
  }

  async goto() {
    await this.page.goto("/roadmap/timeline");
    await expect(this.heading).toBeVisible({ timeout: 15_000 });
  }

  /** Locates an Epic lane header by its raw title (matches the lane node's `data-label`). */
  laneByLabel(label: string): Locator {
    return this.page.locator(`[data-testid="roadmap-timeline-epic-lane"][data-label="${label}"]`);
  }

  /** Locates a Story marker by its raw title (matches the marker node's `data-label`). */
  markerByLabel(label: string): Locator {
    return this.page.locator(`[data-testid="roadmap-timeline-story-node"][data-label="${label}"]`);
  }
}
