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

  /** Item-detail hover/click feature (§5, Task 6). */
  readonly itemPreview: Locator;
  readonly detailPanel: Locator;
  readonly detailClose: Locator;
  readonly detailTitle: Locator;
  readonly detailParent: Locator;
  readonly blockingChain: Locator;

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

    this.itemPreview = page.getByTestId("roadmap-timeline-item-preview");
    this.detailPanel = page.getByTestId("roadmap-timeline-detail-panel");
    this.detailClose = page.getByTestId("roadmap-timeline-detail-close");
    this.detailTitle = page.getByTestId("roadmap-timeline-detail-title");
    this.detailParent = page.getByTestId("roadmap-timeline-detail-parent");
    this.blockingChain = page.getByTestId("roadmap-blocking-chain");
  }

  /**
   * Reads the `data-risk` attribute ("none" | "blocked" | "stalled" | "blocked-stalled") off the
   * Story marker or Epic lane node whose `data-label` matches `label` — the node's own
   * blocked/stalled tint and badge state, set by RoadmapTimelineNode (see `@/lib/timelineRisk`).
   */
  async riskFor(label: string): Promise<string | null> {
    const node = this.page.locator(
      `[data-testid="roadmap-timeline-story-node"][data-label="${label}"], [data-testid="roadmap-timeline-epic-lane"][data-label="${label}"]`,
    );
    return node.getAttribute("data-risk");
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
