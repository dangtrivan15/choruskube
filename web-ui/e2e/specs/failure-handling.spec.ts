import { test, expect } from "../fixtures";

test.describe("Failure Handling", () => {
  // e2e-failure-pipeline's entrypoint node hangs and is killed by its 15s node timeout, so
  // the NODE reaches status "failed" (with an errorMessage) and the RUN parks in
  // "awaiting_retry" (a failed node opens a 7-day retry window rather than failing the run).
  // A genuine "failed" RUN is therefore not reachable in e2e — these tests assert the real
  // reachable failure surface: a failed node and an awaiting_retry run.

  test("node failure surfaces the run as awaiting retry in the run list", async ({
    runListPage,
    api,
  }) => {
    test.slow(); // the failure is driven by a node timeout, so allow extra time
    const template = await api.getTemplateByName("e2e-failure-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-failure-list-test",
    });

    const finished = await api.waitForRunStatus(run.id, ["awaiting_retry"], 120_000);
    expect(finished.status).toBe("awaiting_retry");

    await runListPage.goto();
    await runListPage.waitForTableLoad();

    // The run appears in the list and its status badge reads "awaiting retry".
    const runRow = runListPage.page.locator(`[data-run-id="${run.id}"]`);
    await expect(runRow).toBeVisible();
    await expect(runRow).toContainText(/awaiting retry/i);
  });

  test("failed node displays error message in detail panel", async ({
    runMonitorPage,
    api,
  }) => {
    test.slow();
    const template = await api.getTemplateByName("e2e-failure-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-failure-detail-test",
    });

    // Wait for the NODE (not the run) to fail — the run stays in awaiting_retry.
    await api.waitForNodeStatus(run.id, "failing_step", ["failed"], 120_000);

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Select the failed node — the detail panel shows status "failed" and the
    // error region (DetailPanel.tsx: status === "failed" && errorMessage).
    await runMonitorPage.selectNode("failing_step");
    await expect(runMonitorPage.detailStatus).toContainText(/failed/i);
    await expect(runMonitorPage.detailNodeError).toBeVisible();
    await expect(runMonitorPage.detailNodeError).not.toBeEmpty();
  });

  test("run not found shows appropriate message", async ({ page }) => {
    await page.goto("/runs/00000000-0000-0000-0000-000000000000");
    await expect(
      page.getByText(/not found/i).or(page.getByTestId("run-not-found")),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("status filter surfaces awaiting-retry runs", async ({ runListPage, api }) => {
    test.slow();
    const template = await api.getTemplateByName("e2e-failure-pipeline");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: "e2e-filter-retry-test",
    });

    await api.waitForRunStatus(run.id, ["awaiting_retry"], 120_000);

    await runListPage.goto();
    await runListPage.waitForTableLoad();

    // Filtering by "Awaiting Retry" keeps our failed run visible.
    await runListPage.filterByStatus("Awaiting Retry");
    await runListPage.waitForTableLoad();

    const runRow = runListPage.page.locator(`[data-run-id="${run.id}"]`);
    await expect(runRow).toBeVisible();
    await expect(runRow).toContainText(/awaiting retry/i);
  });
});
