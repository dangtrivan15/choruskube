// Verifies the Analytics chart series-color contract in a real browser: the
// Run Trend and Bottlenecks series resolve to pairwise-distinct colors, each
// legend icon's color and dash pattern match the series it describes, legend
// labels render in the page ink rather than the series color, Total paints on
// top of Completed, and every series re-resolves (not a stale hex) after a
// theme toggle. See src/lib/chartSeriesStyles.ts for the series registry this
// guards and src/lib/__tests__/chart-series-distinguishable.test.ts for the
// per-theme contrast/separation gate this spec cannot express statically.
import { test, expect } from "../fixtures";
import type { RunTrendResponse, BottleneckResponse } from "../../src/lib/types";

// Four consecutive days, including a day where total === completed (the case
// that used to hide Total entirely behind Completed) and a day with failures.
const TREND: RunTrendResponse = {
  points: [
    { date: "2026-01-05", total: 4, completed: 4, failed: 0 },
    { date: "2026-01-06", total: 6, completed: 3, failed: 3 },
    { date: "2026-01-07", total: 5, completed: 5, failed: 0 },
    { date: "2026-01-08", total: 3, completed: 1, failed: 2 },
  ],
};

const BOTTLENECKS: BottleneckResponse = {
  bottlenecks: [
    { label: "build", avgDurationSeconds: 45, p50DurationSeconds: 40, p95DurationSeconds: 120, sampleSize: 12 },
    { label: "test", avgDurationSeconds: 30, p50DurationSeconds: 28, p95DurationSeconds: 90, sampleSize: 12 },
  ],
};

interface Measurement {
  completed: string;
  failed: string;
  total: string;
  avg: string;
  p95: string;
  completedLegend: string;
  failedLegend: string;
  totalLegend: string;
  avgLegend: string;
  p95Legend: string;
  completedDash: string | null;
  failedDash: string | null;
  totalDash: string | null;
  completedLegendDash: string | null;
  failedLegendDash: string | null;
  totalLegendDash: string | null;
  completedLabelColor: string;
  failedLabelColor: string;
  totalLabelColor: string;
  avgLabelColor: string;
  p95LabelColor: string;
  ink: string;
}

test.describe("Analytics chart series colors", () => {
  test("run-trend and bottleneck series are pairwise distinct, legend-matched, and re-resolve after a theme toggle", async ({
    analyticsPage,
    page,
  }) => {
    // The live daily trend endpoint is not zero-filled, and the e2e database is
    // wiped per suite, so a live Run Trend holds one point and draws no curve.
    await page.route(/\/api\/v1\/analytics\/runs\?/, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(TREND) }),
    );
    await page.route(/\/api\/v1\/analytics\/bottlenecks\?/, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(BOTTLENECKS) }),
    );

    await analyticsPage.goto();

    await expect(
      analyticsPage.runTrendChart.locator('[aria-label="Total legend icon"]'),
    ).toBeVisible({ timeout: 15_000 });
    await expect(
      analyticsPage.runTrendChart.locator(".recharts-area.series-total .recharts-area-curve"),
    ).toBeAttached();
    await expect(
      analyticsPage.bottleneckChart.locator(".recharts-bar.series-avg .recharts-bar-rectangle path").first(),
    ).toBeVisible();

    async function measure(): Promise<Measurement> {
      const [completed, failed, total] = await Promise.all([
        analyticsPage.areaStroke("completed"),
        analyticsPage.areaStroke("failed"),
        analyticsPage.areaStroke("total"),
      ]);
      const [avg, p95] = await Promise.all([
        analyticsPage.barFill("avg"),
        analyticsPage.barFill("p95"),
      ]);
      const [completedLegend, failedLegend, totalLegend] = await Promise.all([
        analyticsPage.legendIconColor(analyticsPage.runTrendChart, "Completed", "stroke"),
        analyticsPage.legendIconColor(analyticsPage.runTrendChart, "Failed", "stroke"),
        analyticsPage.legendIconColor(analyticsPage.runTrendChart, "Total", "stroke"),
      ]);
      const [avgLegend, p95Legend] = await Promise.all([
        analyticsPage.legendIconColor(analyticsPage.bottleneckChart, "Avg", "fill"),
        analyticsPage.legendIconColor(analyticsPage.bottleneckChart, "P95", "fill"),
      ]);
      const [completedDash, failedDash, totalDash] = await Promise.all([
        analyticsPage.areaDash("completed"),
        analyticsPage.areaDash("failed"),
        analyticsPage.areaDash("total"),
      ]);
      const [completedLegendDash, failedLegendDash, totalLegendDash] = await Promise.all([
        analyticsPage.legendIconDash(analyticsPage.runTrendChart, "Completed"),
        analyticsPage.legendIconDash(analyticsPage.runTrendChart, "Failed"),
        analyticsPage.legendIconDash(analyticsPage.runTrendChart, "Total"),
      ]);
      const [completedLabelColor, failedLabelColor, totalLabelColor, avgLabelColor, p95LabelColor] =
        await Promise.all([
          analyticsPage.legendLabelColor(analyticsPage.runTrendChart, "Completed"),
          analyticsPage.legendLabelColor(analyticsPage.runTrendChart, "Failed"),
          analyticsPage.legendLabelColor(analyticsPage.runTrendChart, "Total"),
          analyticsPage.legendLabelColor(analyticsPage.bottleneckChart, "Avg"),
          analyticsPage.legendLabelColor(analyticsPage.bottleneckChart, "P95"),
        ]);
      const ink = await analyticsPage.inkColor();

      return {
        completed,
        failed,
        total,
        avg,
        p95,
        completedLegend,
        failedLegend,
        totalLegend,
        avgLegend,
        p95Legend,
        completedDash,
        failedDash,
        totalDash,
        completedLegendDash,
        failedLegendDash,
        totalLegendDash,
        completedLabelColor,
        failedLabelColor,
        totalLabelColor,
        avgLabelColor,
        p95LabelColor,
        ink,
      };
    }

    function assertMeasurement(m: Measurement) {
      expect(new Set([m.completed, m.failed, m.total]).size).toBe(3);
      expect(m.avg).not.toBe(m.p95);

      expect(m.completedLegend).toBe(m.completed);
      expect(m.failedLegend).toBe(m.failed);
      expect(m.totalLegend).toBe(m.total);
      expect(m.avgLegend).toBe(m.avg);
      expect(m.p95Legend).toBe(m.p95);

      for (const labelColor of [
        m.completedLabelColor,
        m.failedLabelColor,
        m.totalLabelColor,
        m.avgLabelColor,
        m.p95LabelColor,
      ]) {
        expect(labelColor).toBe(m.ink);
      }

      expect(m.completedDash).toBeNull();
      expect(m.totalDash).toBe("6 4");
      expect(m.failedDash).toBe("2 3");
      expect(m.completedLegendDash).toBeNull();
      expect(m.totalLegendDash).toBe("6 4");
      expect(m.failedLegendDash).toBe("2 3");
    }

    const before = await measure();
    assertMeasurement(before);

    // Paint order is theme-independent — assert once. SVG paints later
    // siblings on top, so Total (last) covers a coincident Completed line.
    expect(await analyticsPage.areaPaintOrder()).toEqual([
      "series-completed",
      "series-failed",
      "series-total",
    ]);

    const startedDark = await analyticsPage.isDark();
    await analyticsPage.toggleTheme();
    await expect.poll(() => analyticsPage.isDark()).toBe(!startedDark);

    const after = await measure();
    assertMeasurement(after);

    // Re-resolved from the new theme's tokens, not stale hexes from before the toggle.
    expect(after.completed).not.toBe(before.completed);
    expect(after.failed).not.toBe(before.failed);
    expect(after.total).not.toBe(before.total);
    expect(after.avg).not.toBe(before.avg);
    expect(after.p95).not.toBe(before.p95);
    expect(after.ink).not.toBe(before.ink);
  });
});
