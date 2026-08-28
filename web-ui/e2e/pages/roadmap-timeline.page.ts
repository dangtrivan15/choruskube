import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";

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

  /** Shared Roadmap header control — ticket type, view types, and the Graph action. */
  readonly viewControls: RoadmapViewControls;
  /** The Graph action — a disabled `<button>` until an Epic is focused. */
  readonly graphAction: Locator;
  readonly boardViewLink: Locator;

  /** Item-detail hover/click feature. */
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

    this.viewControls = new RoadmapViewControls(page);
    this.graphAction = this.viewControls.graphAction;
    this.boardViewLink = this.viewControls.view("board");

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

  /**
   * Hovers `marker` and waits for the item-detail hover preview (Tooltip) to open and read as
   * expected, re-hovering from scratch on failure rather than asserting once.
   *
   * The Timeline's X-axis is a single linear scale built from every Story's `createdAt` across
   * the *whole org* (`buildTimeScale`, src/lib/timelineLayout.ts), not just the focused Epic's —
   * so any other spec/worker/user creating or deleting a Story anywhere shifts every marker's `x`
   * on the next `["epics"]`-prefixed refetch (`useRoadmapSubscription` invalidates on every
   * `/topic/roadmap-items` event, org-wide, by design — see the existing "reflects a Story
   * created out-of-band" test). A `hover()` that fires once and then waits can lose the race: the
   * marker slides out from under an already-resting cursor before the Tooltip's hover-rest delay
   * elapses, and a CSS-transform move alone doesn't re-fire `mouseenter`/`mouseleave`, so the
   * preview just never opens. Re-issuing `hover()` inside `toPass` re-measures the marker's
   * *current* position each attempt — the same "re-measure and retry" fix `RoadmapBoardPage`
   * already uses for the analogous drag-interruption flake (see web-ui/e2e/PARALLELISM.md).
   */
  async hoverToRevealPreview(marker: Locator, expectedTexts: string[]): Promise<void> {
    await expect(async () => {
      await marker.hover();
      await expect(this.itemPreview).toBeVisible({ timeout: 2_000 });
      for (const text of expectedTexts) {
        await expect(this.itemPreview).toContainText(text, { timeout: 2_000 });
      }
    }).toPass({ timeout: 20_000 });
  }
}
