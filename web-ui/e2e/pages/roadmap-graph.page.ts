import { type Page, type Locator, expect } from "@playwright/test";
import { RoadmapViewControls } from "./roadmap-view-controls.page";

/**
 * Page object for the Roadmap Graph View (/roadmap/epics/:epicId/graph) — an
 * Epic's Story/Task tree plus blocking dependency edges, rendered with
 * @xyflow/react + ELK.
 */
export class RoadmapGraphPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly backToEpicLink: Locator;
  readonly graphContainer: Locator;
  readonly nodes: Locator;

  /** Shared Roadmap header control — ticket type, view types, and the Graph action. */
  readonly viewControls: RoadmapViewControls;
  readonly boardViewLink: Locator;
  readonly timelineViewLink: Locator;

  readonly detailPanel: Locator;
  readonly detailTitle: Locator;
  readonly detailStatus: Locator;
  readonly detailDescription: Locator;
  readonly detailClose: Locator;
  readonly taskRunHistoryList: Locator;

  readonly blockingDependencies: Locator;
  readonly blockingDependencyBadges: Locator;
  readonly blockingDependencyRemoveButtons: Locator;
  readonly addBlockerSelect: Locator;
  readonly addBlockerSubmit: Locator;

  readonly externalBlockers: Locator;
  readonly externalBlockerBadges: Locator;

  readonly externalNodes: Locator;
  readonly crossEpicEdges: Locator;

  readonly blockingChainSection: Locator;
  readonly blockingChainNodes: Locator;
  readonly blockingChainTruncatedNotice: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("roadmap-graph-heading");
    this.backToEpicLink = page.getByRole("link", { name: "Back to Epic" });
    this.graphContainer = page.getByTestId("roadmap-graph-container");
    this.nodes = page.getByTestId("roadmap-graph-node");

    this.viewControls = new RoadmapViewControls(page);
    this.boardViewLink = this.viewControls.view("board");
    this.timelineViewLink = this.viewControls.view("timeline");

    this.detailPanel = page.getByTestId("roadmap-detail-panel");
    this.detailTitle = page.getByTestId("roadmap-detail-title");
    this.detailStatus = page.getByTestId("roadmap-detail-status");
    this.detailDescription = page.getByTestId("roadmap-detail-description");
    this.detailClose = page.getByTestId("roadmap-graph-detail-close");
    this.taskRunHistoryList = page.getByTestId("task-run-history-list");

    this.blockingDependencies = page.getByTestId("roadmap-blocking-dependencies");
    this.blockingDependencyBadges = page.getByTestId("roadmap-blocking-dependency-badge");
    this.blockingDependencyRemoveButtons = page.getByTestId("roadmap-blocking-dependency-remove");
    this.addBlockerSelect = page.getByTestId("roadmap-add-blocker-select");
    this.addBlockerSubmit = page.getByTestId("roadmap-add-blocker-submit");

    this.externalBlockers = page.getByTestId("roadmap-external-blockers");
    this.externalBlockerBadges = page.getByTestId("roadmap-external-blocker-badge");

    // Canvas equivalents of the sidebar's external-blocker list:
    // a real React Flow node/edge pair, not just a text mention. Mirrors the
    // `data-id^="dep:"` pattern already used for the within-Epic dependency
    // edge — see roadmapCrossEpicEdgeId's "cross-epic:" prefix in
    // src/lib/elkLayout.ts.
    this.externalNodes = page.getByTestId("roadmap-external-node");
    this.crossEpicEdges = page.locator('.react-flow__edge[data-id^="cross-epic:"]');

    this.blockingChainSection = page.getByTestId("roadmap-blocking-chain");
    this.blockingChainNodes = page.getByTestId("roadmap-blocking-chain-node");
    this.blockingChainTruncatedNotice = page.getByTestId("roadmap-blocking-chain-truncated");
  }

  async goto(epicId: string) {
    await this.page.goto(`/roadmap/epics/${epicId}/graph`);
    await expect(this.heading).toBeVisible({ timeout: 15_000 });
    await expect(this.graphContainer).toHaveAttribute("data-elk-ready", "true", { timeout: 15_000 });
  }

  /** Locates a graph node by its raw title (matches RoadmapGraphNode's `data-label`). */
  nodeByLabel(label: string): Locator {
    return this.page.locator(`[data-testid="roadmap-graph-node"][data-label="${label}"]`);
  }

  /** Locates an external-blocker link by the blocker item's own title (ExternalBlockersSection). */
  externalBlockerLink(title: string): Locator {
    return this.externalBlockerBadges.filter({ hasText: title });
  }

  /** Locates a canvas external node (RoadmapExternalNode) by the blocker item's own title. */
  externalNodeByLabel(title: string): Locator {
    return this.externalNodes.filter({ hasText: title });
  }

  async selectNode(label: string) {
    await this.nodeByLabel(label).click();
    await expect(this.detailPanel).toBeVisible();
    await expect(this.detailTitle).toContainText(label);
  }

  async toggleCollapse(label: string) {
    await this.nodeByLabel(label).getByTestId("roadmap-graph-node-toggle-collapse").click();
  }

  async isCollapsed(label: string): Promise<boolean> {
    return (await this.nodeByLabel(label).getAttribute("data-collapsed")) === "true";
  }

  /**
   * Adds a blocking dependency on the currently-selected node via the detail
   * panel's picker. `blockerItemTitle` is the title of the Story/Task that
   * should block the selected item. The picker (BlockingDependenciesSection
   * in RoadmapGraphDetailPanel) is the shared shadcn/Base UI `Select`, not a
   * native `<select>` — open it by clicking the trigger, then click the
   * matching option, mirroring the pattern other page objects use for the
   * same component (e.g. run-list.page.ts's filterByStatus/startRun).
   */
  async addBlocker(blockerItemTitle: string) {
    await this.addBlockerSelect.click();
    await this.page.getByRole("option", { name: blockerItemTitle }).click();
    await this.addBlockerSubmit.click();
  }
}
