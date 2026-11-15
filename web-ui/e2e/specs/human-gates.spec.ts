import type { Locator } from "@playwright/test";
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import type { ApprovalsPage } from "../pages/approvals.page";

/**
 * The approvals page card for the run named `runName`, once it is visible. The /pending-gates
 * projection can lag the node-status read, and the list query only auto-refetches every 15s, so
 * this reloads until the card appears. The approvals page lists every pending gate and many share
 * the "review_gate" label, so a card is found by its run's unique name.
 */
async function gateCard(approvalsPage: ApprovalsPage, runName: string): Promise<Locator> {
  await approvalsPage.goto();
  await approvalsPage.waitForGateCards();
  const card = approvalsPage.gateCards.filter({ hasText: runName });
  await expect(async () => {
    if ((await card.count()) === 0) {
      await approvalsPage.page.reload();
      await approvalsPage.waitForGateCards();
    }
    await expect(card).toBeVisible();
  }).toPass({ timeout: 30_000 });
  return card;
}

test.describe("Human Gates", () => {
  test("approvals page lists an open gate under a pending-count badge", async ({
    approvalsPage,
    parkedGateRun,
  }) => {
    await gateCard(approvalsPage, parkedGateRun.name!);
    await expect(approvalsPage.pendingBadge).toBeVisible();
    await expect(approvalsPage.pendingBadge).toContainText("pending");
  });

  test("human gate in run monitor shows approve/reject buttons", async ({
    runMonitorPage,
    parkedGateRun,
  }) => {
    await runMonitorPage.goto(parkedGateRun.id);

    // Find and click the gate node
    await runMonitorPage.selectNode("review_gate");

    // Approve/reject buttons should be visible
    await expect(runMonitorPage.gateApproveButton).toBeVisible();
    await expect(runMonitorPage.gateRejectButton).toBeVisible();
    await expect(runMonitorPage.gateFeedbackInput).toBeVisible();
  });

  test("reject button requires feedback text", async ({ runMonitorPage, parkedGateRun }) => {
    await runMonitorPage.goto(parkedGateRun.id);
    await runMonitorPage.selectNode("review_gate");

    // Reject button should be disabled when feedback is empty
    await expect(runMonitorPage.gateRejectButton).toBeDisabled();

    // After typing feedback, reject should become enabled — never clicked: the gate is shared.
    await runMonitorPage.gateFeedbackInput.fill("Needs revision");
    await expect(runMonitorPage.gateRejectButton).toBeEnabled();
  });

  test("approve gate advances the workflow", async ({ runMonitorPage, api }) => {
    const template = await api.getTemplateByName("e2e-human-gate");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-approve-gate-test",
    });

    await api.waitForNodeStatus(
      run.id,
      "review_gate",
      ["awaiting_human"],
      60_000,
    );

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("review_gate");

    // Approve the gate through the UI — this signals the workflow.
    await runMonitorPage.approveGate("Looks good, approved!");

    // The approved edge routes draft -> review_gate -> publish; the run must
    // complete. The gate node ends "completed", and publish runs to completion.
    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");
    await api.waitForNodeStatus(run.id, "review_gate", ["completed"], 10_000);
    await api.waitForNodeStatus(run.id, "publish", ["completed"], 10_000);
  });

  test("approvals page gate card shows flat artifact list when requiredArtifacts present", async ({
    approvalsPage,
    parkedGateRun,
  }) => {
    // The draft node uploads an artifact named "draft-output" (mock-agent
    // `--artifact draft-output`), matching the gate's requiredInputArtifacts, so the gate
    // card's ArtifactList renders it. Scope to THIS run's card — the approvals page lists
    // every pending gate, so several artifact lists are present at once.
    const card = await gateCard(approvalsPage, parkedGateRun.name!);

    const artifactList = card.getByTestId("artifact-list");
    await expect(artifactList).toBeVisible();
    const firstItem = artifactList.getByTestId("artifact-list-items").locator("li").first();
    await expect(firstItem).toContainText("/");
    await expect(card.getByTestId("artifact-browser")).not.toBeVisible();
  });

  test("run detail panel shows flat artifact list when requiredArtifacts present", async ({
    runMonitorPage,
    parkedGateRun,
  }) => {
    await runMonitorPage.goto(parkedGateRun.id);
    await runMonitorPage.selectNode("review_gate");
    await expect(runMonitorPage.artifactList).toBeVisible();
    await expect(runMonitorPage.page.getByTestId("artifact-browser")).not.toBeVisible();
  });

  test("flat artifact list items open viewer dialog on click", async ({
    runMonitorPage,
    parkedGateRun,
  }) => {
    await runMonitorPage.goto(parkedGateRun.id);
    await runMonitorPage.selectNode("review_gate");
    await runMonitorPage.artifactListItems.first().click();
    await expect(runMonitorPage.page.getByTestId("artifact-viewer-dialog")).toBeVisible();
  });

  test("gate card on approvals page allows approve action", async ({
    approvalsPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-human-gate");

    // Use a SHORT unique run name to isolate THIS gate's card — the approvals
    // page lists every pending gate and many share the "review_gate" node label,
    // so scope by run name (rendered on the card). Keep it short: the card
    // truncates long names with an ellipsis, which breaks an exact hasText match.
    const runName = uniqueName("appr");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: runName,
    });

    await api.waitForNodeStatus(
      run.id,
      "review_gate",
      ["awaiting_human"],
      60_000,
    );
    await gateCard(approvalsPage, runName);

    // Approve it from the approvals page — this signals the workflow.
    await approvalsPage.approveGate(runName, "Approved via approvals page");

    // The run must advance to completion past the approved gate.
    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");
  });

  // Regression coverage for the v23 contract-drift bug where /approvals
  // sent decision:"rejected" on approve_spec_and_plan and the API server
  // returned 500 — the browser-level twin of the Vitest spec in
  // src/pages/__tests__/ApprovalsPage.test.tsx ("renders Approve /
  // Re-review / Redraft buttons (no Reject) for v23 spec gates").
  test("approvals page renders Approve / Re-review / Redraft for v23 spec gate", async ({
    approvalsPage,
    api,
  }) => {
    // e2e-spec-and-plan-gate's approve_spec_and_plan node has outgoing edges
    // approved/rereview/redraft, which PendingGateService surfaces as decisionOptions
    // so the gate card renders Approve / Re-review / Redraft (and no Reject).
    const template = await api.getTemplateByName("e2e-spec-and-plan-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-v23-spec-gate",
    });

    await api.waitForNodeStatus(
      run.id,
      "approve_spec_and_plan",
      ["awaiting_human"],
      60_000,
    );

    try {
      await approvalsPage.goto();
      await approvalsPage.waitForGateCards();

      const card = approvalsPage.gateCards.filter({ hasText: "approve_spec_and_plan" });
      await expect(card.getByTestId("gate-card-approve-button")).toBeVisible();
      await expect(card.getByTestId("gate-card-rereview-button")).toBeVisible();
      await expect(card.getByTestId("gate-card-redraft-button")).toBeVisible();
      await expect(card.getByTestId("gate-card-reject-button")).toHaveCount(0);

      // The server accepted the decision the card sent: the gate's first pass closed on it.
      await approvalsPage.rereviewGate("approve_spec_and_plan", "Tighten");
      await expect
        .poll(
          async () =>
            (await api.getRun(run.id)).nodeExecutions.find(
              (ne) => ne.label === "approve_spec_and_plan" && ne.iteration === 1,
            )?.decision,
          { timeout: 30_000, intervals: [1_000] },
        )
        .toBe("rereview");
    } finally {
      // The rereview loops back to the gate, which would otherwise stay open for the rest of the suite.
      await api.cancelRun(run.id).catch(() => {});
    }
  });
});
