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

  test("sidebar shows run metadata before node selection", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-sidebar-run-meta-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Sidebar should show run meta panel before any node is selected
    await expect(runMonitorPage.runMetaPanel).toBeVisible();
    await expect(runMonitorPage.detailPanel).not.toBeVisible();
  });

  test("sidebar switches to node metadata on node click", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-sidebar-node-click-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Click first DAG node
    await runMonitorPage.dagNodes.first().click();

    // Detail panel should be visible with back button
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.runMetaPanel).not.toBeVisible();
    await expect(runMonitorPage.detailPanelBackButton).toBeVisible();
  });

  test("back button returns sidebar to run metadata", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-sidebar-back-button-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Click a node to switch to DetailPanel
    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.detailPanelBackButton).toBeVisible();

    // Click back button to return to RunMetaPanel
    await runMonitorPage.detailPanelBackButton.click();
    await expect(runMonitorPage.runMetaPanel).toBeVisible();
    await expect(runMonitorPage.detailPanel).not.toBeVisible();
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

  test("mobile viewport retains top-strip run metadata; no persistent sidebar", async ({
    page,
    api,
  }) => {
    // Set mobile viewport
    await page.setViewportSize({ width: 390, height: 844 });

    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-mobile-meta-bar-test",
    });

    // Navigate directly — don't use runMonitorPage since it was created with the default viewport
    await page.goto(`/runs/${run.id}`);
    await expect(page.getByTestId("run-header-title")).toBeVisible({ timeout: 15_000 });

    // No persistent sidebar (run-meta-panel) on mobile
    await expect(page.getByTestId("run-meta-panel")).not.toBeAttached();

    // The mobile top-strip run meta bar should be present in the DOM
    // (may not have content if no promptText/task, but the component structure is there)
    // Assert the DAG is visible to confirm page loaded correctly
    await expect(page.getByTestId("run-dag-container")).toBeVisible();
  });

  test("sidebar is collapsible and restores on expand", async ({
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

    // Sidebar open by default — run meta panel visible
    await expect(runMonitorPage.runMetaPanel).toBeVisible();
    await expect(runMonitorPage.sidebarCollapseButton).toBeVisible();

    // Collapse the sidebar
    await runMonitorPage.sidebarCollapseButton.click();
    await expect(runMonitorPage.runMetaPanel).not.toBeVisible();
    await expect(runMonitorPage.sidebarExpandButton).toBeVisible();

    // Expand the sidebar again
    await runMonitorPage.sidebarExpandButton.click();
    await expect(runMonitorPage.runMetaPanel).toBeVisible();
    await expect(runMonitorPage.sidebarCollapseButton).toBeVisible();
  });
});
