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

  // Run meta panel (sidebar)
  readonly runMetaPanel: Locator;
  readonly detailPanelBackButton: Locator;
  readonly sidebarCollapseButton: Locator;
  readonly sidebarExpandButton: Locator;

  // Detail panel
  readonly detailPanel: Locator;
  readonly detailNodeLabel: Locator;
  readonly detailStatus: Locator;
  readonly detailNodeError: Locator;

  // Human gate elements (in detail panel)
  readonly gateFeedbackInput: Locator;
  readonly gateApproveButton: Locator;
  readonly gateRejectButton: Locator;
  readonly gateRereviewButton: Locator;
  readonly gateRedraftButton: Locator;

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

    this.runMetaPanel = page.getByTestId("run-meta-panel");
    this.detailPanelBackButton = page.getByTestId("detail-panel-back-button");
    this.sidebarCollapseButton = page.getByTestId("sidebar-collapse-button");
    this.sidebarExpandButton = page.getByTestId("sidebar-expand-button");

    this.detailPanel = page.getByTestId("detail-panel");
    this.detailNodeLabel = page.getByTestId("detail-node-label");
    this.detailStatus = page.getByTestId("detail-node-status");
    this.detailNodeError = page.getByTestId("detail-node-error");

    this.gateFeedbackInput = page.getByTestId("gate-feedback-input");
    this.gateApproveButton = page.getByTestId("gate-approve-button");
    this.gateRejectButton = page.getByTestId("gate-reject-button");
    // v23 spec gate actions — see DecisionButtons.tsx for the mapping
    this.gateRereviewButton = page.getByTestId("gate-rereview-button");
    this.gateRedraftButton = page.getByTestId("gate-redraft-button");

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
  }

  async expectNodeStatus(nodeLabel: string, status: string) {
    const node = this.page.locator(`[data-testid="dag-node"][data-label="${nodeLabel}"]`);
    await expect(node).toContainText(status);
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
}
