import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Run Monitor page (/runs/:id).
 */
export class RunMonitorPage {
  readonly page: Page;

  // Header
  readonly runTitle: Locator;
  readonly runStatus: Locator;
  readonly pauseButton: Locator;
  readonly resumeButton: Locator;
  readonly cancelButton: Locator;
  /** Present only when the Autopilot started this run, absent when a person did. */
  readonly autopilotBadge: Locator;

  // DAG
  readonly dagContainer: Locator;
  readonly dagNodes: Locator;

  // Run summary (strip on tablet/desktop, mobile bar + sheets on phone)
  readonly runSummary: Locator;
  readonly roadmapBreadcrumb: Locator;
  readonly mobileBar: Locator;
  readonly runInfoOpenButton: Locator;
  readonly runInfoSheet: Locator;
  readonly attentionButton: Locator;
  readonly actionsMenuTrigger: Locator;

  // Sidebar / node panel
  readonly nodeDetailEmpty: Locator;
  readonly sidebarCollapseButton: Locator;
  readonly sidebarExpandButton: Locator;

  // Detail panel
  readonly detailPanel: Locator;
  readonly detailPanelClose: Locator;
  readonly detailNodeLabel: Locator;
  readonly detailStatus: Locator;
  readonly detailNodeError: Locator;
  /** The phone/tablet bottom sheet wrapping the node detail panel. */
  readonly nodeSheet: Locator;

  // Human gate elements (in detail panel)
  readonly gateFeedbackInput: Locator;
  readonly gateApproveButton: Locator;
  readonly gateRejectButton: Locator;
  readonly gateRereviewButton: Locator;
  readonly gateRedraftButton: Locator;
  /** Pre-approval callout on a merge-configured gate, listing the PRs approval will merge. */
  readonly mergeNotice: Locator;
  /** Merged/Open/Closed badge on a pull-request link — one per PR shown. */
  readonly pullRequestStates: Locator;

  // Execution logs
  readonly executionLogs: Locator;

  // Artifact list
  readonly artifactList: Locator;
  readonly artifactListItems: Locator;
  readonly artifactBrowserItems: Locator;

  // Artifact viewer dialog (ArtifactViewerDialog.tsx)
  readonly artifactViewerDialog: Locator;
  readonly artifactViewerContent: Locator;
  readonly artifactFileSwitcher: Locator;

  constructor(page: Page) {
    this.page = page;

    this.runTitle = page.getByTestId("run-header-title");
    this.runStatus = page.getByTestId("run-header-status");
    this.autopilotBadge = page.getByTestId("autopilot-run-badge");
    this.pauseButton = page.getByTestId("run-pause-button");
    this.resumeButton = page.getByTestId("run-resume-button");
    this.cancelButton = page.getByTestId("run-cancel-button");

    this.dagContainer = page.getByTestId("run-dag-container");
    this.dagNodes = page.getByTestId("dag-node");

    this.runSummary = page.getByTestId("run-summary");
    this.roadmapBreadcrumb = page.getByTestId("roadmap-breadcrumb");
    this.mobileBar = page.getByTestId("run-summary-mobile-bar");
    this.runInfoOpenButton = page.getByTestId("run-info-open-button");
    this.runInfoSheet = page.getByTestId("run-info-sheet");
    this.attentionButton = page.getByTestId("run-attention-button");
    this.actionsMenuTrigger = page.getByTestId("run-actions-menu-trigger");

    this.nodeDetailEmpty = page.getByTestId("node-detail-empty");
    this.sidebarCollapseButton = page.getByTestId("sidebar-collapse-button");
    this.sidebarExpandButton = page.getByTestId("sidebar-expand-button");

    this.detailPanel = page.getByTestId("detail-panel");
    this.detailPanelClose = page.getByTestId("detail-panel-close-button");
    this.detailNodeLabel = page.getByTestId("detail-node-label");
    this.detailStatus = page.getByTestId("detail-node-status");
    this.detailNodeError = page.getByTestId("detail-node-error");
    this.nodeSheet = page.getByTestId("mobile-detail-overlay");

    this.gateFeedbackInput = page.getByTestId("gate-feedback-input");
    this.gateApproveButton = page.getByTestId("gate-approve-button");
    this.gateRejectButton = page.getByTestId("gate-reject-button");
    // v23 spec gate actions — see DecisionButtons.tsx for the mapping
    this.gateRereviewButton = page.getByTestId("gate-rereview-button");
    this.gateRedraftButton = page.getByTestId("gate-redraft-button");
    this.mergeNotice = page.getByTestId("merge-on-approval-notice");
    this.pullRequestStates = page.getByTestId("pull-request-state");

    this.executionLogs = page.getByTestId("execution-logs");

    this.artifactList = page.getByTestId("artifact-list");
    this.artifactListItems = page.getByTestId("artifact-list-items").locator("li");
    // Note: artifactListItems is backed by ArtifactList.tsx, reachable only from
    // gate/approval surfaces (human-gates.spec.ts). A normal script node's artifacts
    // render via ArtifactBrowser.tsx instead, which is why artifactBrowserItems exists
    // as a separate locator rather than reusing artifactListItems.
    this.artifactBrowserItems = page.getByTestId("artifact-browser-items").locator("li");

    this.artifactViewerDialog = page.getByTestId("artifact-viewer-dialog");
    this.artifactViewerContent = page.getByTestId("artifact-viewer-content");
    this.artifactFileSwitcher = page.getByTestId("artifact-file-switcher");
  }

  async goto(runId: string) {
    await this.page.goto(`/runs/${runId}`);
    await expect(this.runTitle).toBeVisible({ timeout: 15_000 });
  }

  async waitForStatus(status: string | RegExp) {
    if (typeof status === "string") {
      await expect(this.runStatus).toContainText(status, { timeout: 30_000 });
    } else {
      await expect(this.runStatus).toContainText(status, { timeout: 30_000 });
    }
  }

  async selectNode(nodeLabel: string) {
    // Match against the raw `data-label` (the snake_case template slug) so
    // tests don't depend on display-time label formatting (prefix-stripping,
    // title-casing, etc).
    const node = this.page.locator(`[data-testid="dag-node"][data-label="${nodeLabel}"]`);
    await node.click();
    await expect(this.detailPanel).toBeVisible();
    // On desktop, auto-focus may already have a (different) node's panel open before
    // this click, so visibility alone doesn't prove the click selected this node —
    // the label must match too. Works for the docked panel and the sheet alike:
    // `detail-node-label` shows the raw `data-label`, same as the DAG node above.
    await expect(this.detailNodeLabel).toHaveText(nodeLabel);
  }

  /** Waits for the DAG's initial viewport computation (fit/zoom) to have applied. */
  async waitForViewportReady() {
    await expect(
      this.page.locator('[data-testid="run-dag-container"][data-viewport-ready="true"]'),
    ).toBeVisible();
  }

  async expectNodeStatus(nodeLabel: string, status: string) {
    const node = this.page.locator(`[data-testid="dag-node"][data-label="${nodeLabel}"]`);
    await expect(node).toContainText(status);
  }

  /**
   * Computed text color of a DAG node's status **icon** — the resolved hex of
   * whichever `text-status-*` token `statusColorTokens()` (src/lib/statusColors.ts)
   * assigned this status, read straight from the rendered DOM rather than the
   * source map so a real browser's CSS cascade is what's asserted on. The
   * status *word* is uniform ink now (see `nodeStatusLabelColor`) — the tone
   * lives on this icon mark instead.
   */
  async nodeStatusColor(nodeLabel: string): Promise<string> {
    const node = this.page.locator(`[data-testid="dag-node"][data-label="${nodeLabel}"]`);
    return node.getByTestId("dag-node-status-icon").evaluate((el) => getComputedStyle(el).color);
  }

  /** Computed text color of a DAG node's status *word* — expected to resolve to `var(--foreground)` for every status. */
  async nodeStatusLabelColor(nodeLabel: string): Promise<string> {
    const node = this.page.locator(`[data-testid="dag-node"][data-label="${nodeLabel}"]`);
    return node.locator("span.capitalize").evaluate((el) => getComputedStyle(el).color);
  }

  /** A log row in the execution log panel for a given severity level. */
  logRow(level: string): Locator {
    return this.executionLogs.locator(`[data-testid="log-row"][data-level="${level}"]`);
  }

  /**
   * Computed text color of a severity row's **glyph** — the resolved hex of
   * whichever `text-status-*` token `logLevelStyle()` (src/lib/logLevelStyles.ts)
   * assigned this level, read from the rendered DOM so a real browser's CSS
   * cascade is what's asserted on. Mirrors `nodeStatusColor`. The level *word*
   * is uniform ink now (see `logSeverityLabelColor`) — the tone lives on this
   * glyph mark instead.
   */
  async logSeverityColor(level: string): Promise<string> {
    return this.logRow(level).first().locator("svg").evaluate((el) => getComputedStyle(el).color);
  }

  /** Computed text color of a severity row's *word* — expected to resolve to `var(--foreground)` for every level. */
  async logSeverityLabelColor(level: string): Promise<string> {
    return this.logRow(level).first().getByTestId("log-level").evaluate((el) => getComputedStyle(el).color);
  }

  /**
   * A severity row's icon glyph — the SVG's markup, independent of color — so
   * a test can assert the three levels use pairwise-distinct icon *shapes*,
   * not just distinct colors.
   */
  async logSeverityGlyph(level: string): Promise<string> {
    return this.logRow(level).first().locator("svg").evaluate((el) => el.innerHTML);
  }

  async approveGate(feedback?: string) {
    if (feedback) {
      await this.gateFeedbackInput.fill(feedback);
    }
    await this.gateApproveButton.click();
  }

  async rejectGate(feedback: string) {
    await this.gateFeedbackInput.fill(feedback);
    await this.gateRejectButton.click();
  }

  async cancelRun() {
    await this.cancelButton.click();
  }

  /** Cancels via the phone actions menu, where lifecycle actions live behind `run-actions-menu-trigger`. */
  async cancelRunFromMenu() {
    await this.actionsMenuTrigger.click();
    await this.cancelButton.click();
  }
}
