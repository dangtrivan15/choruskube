import { test, expect } from "../fixtures";

test.describe("DAG Interaction", () => {
  test("DAG renders with correct number of nodes", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-dag-node-count-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Linear pipeline has 3 nodes: step_1, step_2, step_3
    const nodeCount = await runMonitorPage.dagNodes.count();
    expect(nodeCount).toBeGreaterThanOrEqual(3);
  });

  test("selecting a node shows its label in detail panel", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-dag-select-label-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Click a node
    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.detailPanel).toBeVisible();

    // Detail panel should show the node label
    await expect(runMonitorPage.detailNodeLabel).toBeVisible();
    const labelText = await runMonitorPage.detailNodeLabel.textContent();
    expect(labelText).toBeTruthy();
  });

  test("clicking DAG background deselects node", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-dag-deselect-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Select a node
    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.detailPanel).toBeVisible();

    // Click the DAG background (ReactFlow pane)
    await runMonitorPage.dagContainer.click({ position: { x: 10, y: 10 } });

    // Detail panel should be hidden
    await expect(runMonitorPage.detailPanel).not.toBeVisible();
  });

  test("completed nodes show completed status in DAG", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-dag-completed-test",
    });

    // The linear pipeline is all mock-success nodes — it must complete.
    const finalRun = await api.waitForRunStatus(run.id, ["completed"], 120_000);
    expect(finalRun.status).toBe("completed");

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Every node completed — the DAG must surface "completed" status text.
    await expect(
      runMonitorPage.page.locator('[data-testid="dag-node"]').filter({
        hasText: /completed/i,
      }).first(),
    ).toBeVisible();
  });

  test("parallel fanout template renders multiple branch nodes", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-parallel-fanout");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-dag-parallel-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Parallel fanout has 5 nodes: start, branch_a, branch_b, branch_c, merge
    const nodeCount = await runMonitorPage.dagNodes.count();
    expect(nodeCount).toBeGreaterThanOrEqual(5);
  });

  test("summary strip and node panel are visible together after selecting a node", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-summary-coexist-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Run info is never hidden by node selection any more — this is the core
    // premise of the summary-strip redesign.
    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.runSummary).toBeVisible();
  });

  test("finished run shows the empty node panel", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-empty-panel-test",
    });

    // A clean completed run has nothing needing attention, so nothing is
    // auto-focused — the panel shows the empty state, not a node.
    await api.waitForRunStatus(run.id, ["completed"], 120_000);

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    await expect(runMonitorPage.nodeDetailEmpty).toBeVisible();
    await expect(runMonitorPage.detailPanel).not.toBeVisible();
  });

  test("close button clears selection and the ?node param", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-close-button-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.page).toHaveURL(/[?&]node=/);

    await runMonitorPage.detailPanelClose.click();
    await expect(runMonitorPage.detailPanel).not.toBeVisible();
    await expect(runMonitorPage.page).not.toHaveURL(/[?&]node=/);
  });

  test("minimap is absent from the DAG canvas", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-minimap-absent-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // MiniMap element should not be present in the DOM
    await expect(runMonitorPage.page.locator(".react-flow__minimap")).not.toBeAttached();
  });

  test("panel collapses and expands", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-sidebar-collapse-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Select a node explicitly so the panel's content is deterministic — on
    // this tier, desktop auto-focus may otherwise have already selected a
    // running node by the time the page loads, racing an "empty state"
    // assertion made before any click.
    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.sidebarCollapseButton).toBeVisible();

    // Collapse the panel
    await runMonitorPage.sidebarCollapseButton.click();
    await expect(runMonitorPage.detailPanel).not.toBeVisible();
    await expect(runMonitorPage.sidebarExpandButton).toBeVisible();

    // Expand the panel again
    await runMonitorPage.sidebarExpandButton.click();
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.sidebarCollapseButton).toBeVisible();
  });
});
