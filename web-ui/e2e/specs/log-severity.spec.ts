// Proves info/warn/error log rows are distinguishable in a real browser —
// pairwise-distinct icon glyphs and label colors — and stay distinct and
// recolor after a theme toggle. Mirrors status-colors.spec.ts's structure
// and `toggleTheme` helper.
//
// The e2e seeder never seeds runs or node executions (only templates, node
// definitions, git repos and repo groups), and the real flow that reaches
// all three severities in one node (a failed node's orchestrator-written
// warn + error entries) is best-effort on the warn line. So this spec starts
// a real completed run and intercepts only its node-logs request with
// page.route() to serve a deterministic info/warn/error fixture — following
// the precedent set by pull-request-links.spec.ts, the first user of
// route-mocking in this repo. failure-handling.spec.ts separately asserts a
// real, unstubbed error row on a genuinely failed node.
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { toggleTheme } from "../helpers/colors";

const FIXED_LOGS = [
  { id: "log-info", level: "info", message: "Node started", timestamp: "2026-01-01T00:00:00.000Z" },
  { id: "log-warn", level: "warn", message: "Retrying step", timestamp: "2026-01-01T00:00:01.000Z" },
  { id: "log-error", level: "error", message: "Node failed: boom", timestamp: "2026-01-01T00:00:02.000Z" },
];

test.describe("Log severity styling", () => {
  test("info/warn/error rows are pairwise distinct by glyph and color, and stay distinct and recolor after a theme toggle", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-log-severity"),
    });
    await api.waitForRunStatus(run.id, ["completed"], 120_000);

    await runMonitorPage.page.route(
      `**/api/v1/runs/${run.id}/nodes/*/logs`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(FIXED_LOGS),
        });
      },
    );

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("step_1");

    await expect(runMonitorPage.logRow("info")).toHaveCount(1);
    await expect(runMonitorPage.logRow("warn")).toHaveCount(1);
    await expect(runMonitorPage.logRow("error")).toHaveCount(1);

    const infoGlyph = await runMonitorPage.logSeverityGlyph("info");
    const warnGlyph = await runMonitorPage.logSeverityGlyph("warn");
    const errorGlyph = await runMonitorPage.logSeverityGlyph("error");
    expect(new Set([infoGlyph, warnGlyph, errorGlyph]).size).toBe(3);

    const infoColor = await runMonitorPage.logSeverityColor("info");
    const warnColor = await runMonitorPage.logSeverityColor("warn");
    const errorColor = await runMonitorPage.logSeverityColor("error");
    expect(new Set([infoColor, warnColor, errorColor]).size).toBe(3);

    await toggleTheme(runMonitorPage.page);
    await expect(runMonitorPage.page.locator("html")).toHaveClass(/dark/);

    const darkInfoColor = await runMonitorPage.logSeverityColor("info");
    const darkWarnColor = await runMonitorPage.logSeverityColor("warn");
    const darkErrorColor = await runMonitorPage.logSeverityColor("error");
    expect(new Set([darkInfoColor, darkWarnColor, darkErrorColor]).size).toBe(3);
    // Re-resolved from the new theme's tokens, not stale light-mode hexes.
    expect(darkInfoColor).not.toBe(infoColor);
    expect(darkWarnColor).not.toBe(warnColor);
    expect(darkErrorColor).not.toBe(errorColor);

    // Glyph shape is theme-independent.
    expect(await runMonitorPage.logSeverityGlyph("info")).toBe(infoGlyph);
    expect(await runMonitorPage.logSeverityGlyph("warn")).toBe(warnGlyph);
    expect(await runMonitorPage.logSeverityGlyph("error")).toBe(errorGlyph);
  });
});
