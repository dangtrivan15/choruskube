import { describe, it, expect } from "vitest";
import { CHART_SERIES_STYLES, seriesColor, seriesDashProps } from "../chartSeriesStyles";

describe("CHART_SERIES_STYLES", () => {
  it("has exactly the three charts, each with its exact series keys", () => {
    expect(Object.keys(CHART_SERIES_STYLES).sort()).toEqual(
      ["bottleneck", "roadmapThroughput", "runTrend"].sort(),
    );
    expect(Object.keys(CHART_SERIES_STYLES.runTrend).sort()).toEqual(
      ["completed", "failed", "total"].sort(),
    );
    expect(Object.keys(CHART_SERIES_STYLES.bottleneck).sort()).toEqual(["avg", "p95"].sort());
    expect(Object.keys(CHART_SERIES_STYLES.roadmapThroughput)).toEqual(["done"]);
  });

  it.each([
    ["runTrend", "completed", "--status-success"],
    ["runTrend", "failed", "--status-error"],
    ["runTrend", "total", "--chart-reference"],
    ["bottleneck", "avg", "--chart-4"],
    ["bottleneck", "p95", "--chart-2"],
    ["roadmapThroughput", "done", "--status-success"],
  ] as const)("pins %s.%s's token to %s", (chart, series, token) => {
    expect(
      (CHART_SERIES_STYLES[chart] as Record<string, { token: string }>)[series].token,
    ).toBe(token);
  });

  it.each([
    ["completed", ""],
    ["failed", "2 3"],
    ["total", "6 4"],
  ] as const)("pins runTrend.%s's dashArray to %j", (series, dashArray) => {
    expect(CHART_SERIES_STYLES.runTrend[series].dashArray).toBe(dashArray);
  });

  it("every series has a non-empty, non-'--color-' token, a non-empty label, a valid dashArray, and a valid mark", () => {
    for (const chart of Object.values(CHART_SERIES_STYLES)) {
      for (const style of Object.values(chart)) {
        expect(style.token.startsWith("--")).toBe(true);
        expect(style.token.startsWith("--color-")).toBe(false);
        expect(style.label.length).toBeGreaterThan(0);
        expect(style.dashArray === "" || /^\d+(\s\d+)*$/.test(style.dashArray)).toBe(true);
        expect(["line", "bar"]).toContain(style.mark);
      }
    }
  });

  it("the runTrend line series have pairwise-distinct dashArrays, so Total never looks identical to Completed when every run succeeds", () => {
    const dashArrays = Object.values(CHART_SERIES_STYLES.runTrend).map((s) => s.dashArray);
    expect(new Set(dashArrays).size).toBe(dashArrays.length);
  });

  it("no bottleneck series uses a --status-* token — measurements are not states", () => {
    for (const style of Object.values(CHART_SERIES_STYLES.bottleneck)) {
      expect(style.token.startsWith("--status-")).toBe(false);
    }
  });

  it("no series in any chart uses --foreground", () => {
    for (const chart of Object.values(CHART_SERIES_STYLES)) {
      for (const style of Object.values(chart)) {
        expect(style.token).not.toBe("--foreground");
      }
    }
  });

  it("seriesColor wraps the token in var()", () => {
    expect(seriesColor(CHART_SERIES_STYLES.runTrend.total)).toBe("var(--chart-reference)");
  });

  it("seriesDashProps omits strokeDasharray for a solid series", () => {
    const result = seriesDashProps(CHART_SERIES_STYLES.runTrend.completed);
    expect("strokeDasharray" in result).toBe(false);
  });

  it("seriesDashProps returns the dash pattern for a dashed series", () => {
    expect(seriesDashProps(CHART_SERIES_STYLES.runTrend.total)).toEqual({
      strokeDasharray: "6 4",
    });
  });
});
