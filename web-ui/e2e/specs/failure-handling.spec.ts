import { test, expect } from "../fixtures";

test.describe("Failure Handling", () => {
  // e2e-failure-pipeline's entrypoint node hangs and is killed by its 60s node timeout, so the
  // NODE reaches status "failed" (with an errorMessage) and the RUN parks in "awaiting_retry" (a
  // failed node opens a 7-day retry window rather than failing the run). A genuine "failed" RUN is
  // therefore not reachable in e2e — these tests assert the real reachable failure surface: a
  // failed node and an awaiting_retry run, both on the shared `failedRun` fixture.

  test("failed node displays error message in detail panel", async ({
    runMonitorPage,
    failedRun,
  }) => {
    await runMonitorPage.goto(failedRun.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({
      timeout: 15_000,
    });

    // Select the failed node — the detail panel shows status "failed" and the
    // error region (DetailPanel.tsx: status === "failed" && errorMessage).
    await runMonitorPage.selectNode("failing_step");
    await expect(runMonitorPage.detailStatus).toContainText(/failed/i);
    await expect(runMonitorPage.detailNodeError).toBeVisible();
    await expect(runMonitorPage.detailNodeError).not.toBeEmpty();
    await expect(runMonitorPage.logRow("error").first()).toBeVisible();
  });

  test("status filter surfaces awaiting-retry runs", async ({ runListPage, failedRun }) => {
    await runListPage.goto();
    await runListPage.waitForTableLoad();

    // The list pages at 20, newest first, and failedRun is shared from early in the suite: only
    // the filter keeps it on page 1.
    await runListPage.filterByStatus("Awaiting Retry");
    await runListPage.waitForTableLoad();

    const runRow = runListPage.page.locator(`[data-run-id="${failedRun.id}"]`);
    await expect(runRow).toBeVisible();
    await expect(runRow).toContainText(/awaiting retry/i);
  });
});
