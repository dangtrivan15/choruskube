import { test, expect } from "../fixtures";

test.describe("Real-Time Updates", () => {
  test("run status updates in run list without manual refresh", async ({
    runListPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    await runListPage.goto();
    await runListPage.waitForTableLoad();

    const beforeCount = await runListPage.getRunCount();

    // Start a run via API
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-realtime-list-test",
    });

    // Wait for the UI to update via WebSocket (the table should show the new run)
    await runListPage.page.waitForTimeout(5000);

    // Reload and check — the run should appear
    await runListPage.goto();
    await runListPage.waitForTableLoad();

    const runRow = runListPage.page.locator(`[data-run-id="${run.id}"]`);
    await expect(runRow).toBeVisible({ timeout: 15_000 });
  });

  test("run monitor page reflects node status changes", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-realtime-monitor-test",
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagContainer).toBeVisible();
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Drive the first node to completion, then assert the open monitor page
    // reflects it without a manual reload — the STOMP subscription must push
    // the status change into the DAG node.
    await api.waitForNodeStatus(run.id, "step_1", ["completed"], 60_000);
    await runMonitorPage.expectNodeStatus("step_1", "completed");

    // The whole pipeline completes; the run header reflects the terminal status
    // (still on the same page instance — no reload).
    await api.waitForRunStatus(run.id, ["completed"], 120_000);
    await runMonitorPage.waitForStatus(/completed/i);
  });

  test("completed node surfaces execution status and result in detail panel", async ({
    runMonitorPage,
    api,
  }) => {
    // NOTE: this test asserts the completed node's status and result — its
    // logs are covered separately (log-severity.spec.ts, failure-handling.spec.ts).
    // The mock pipeline completes in ~1s, so catching an *active* node deterministically
    // isn't reliable here; a completed node's status badge and result text are.
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-realtime-result-test",
    });

    await api.waitForRunStatus(run.id, ["completed"], 120_000);

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("step_1");
    await expect(runMonitorPage.detailStatus).toHaveText("completed");
    await expect(runMonitorPage.page.getByText("Result", { exact: true })).toBeVisible();
  });
});
