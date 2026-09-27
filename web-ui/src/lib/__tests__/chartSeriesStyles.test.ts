import { describe, it, expect } from "vitest";
import {
  CHART_SERIES_STYLES,
  CHART_TEXT_TOKEN,
  CHART_SURFACE_TOKEN,
  seriesColor,
  seriesDashProps,
  chartTickProps,
  chartTooltipProps,
} from "../chartSeriesStyles";

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

describe("chart text/surface tokens and helper props", () => {
  it("pins CHART_TEXT_TOKEN and CHART_SURFACE_TOKEN", () => {
    expect(CHART_TEXT_TOKEN).toBe("--foreground");
    expect(CHART_SURFACE_TOKEN).toBe("--popover");
  });

  it("chartTickProps(12) returns the given font size with ink fill", () => {
    expect(chartTickProps(12)).toEqual({ fontSize: 12, fill: "var(--foreground)" });
  });

  it("chartTickProps(11) carries the given font size through", () => {
    expect(chartTickProps(11).fontSize).toBe(11);
  });

  it("chartTooltipProps returns an opaque popover surface with ink item/label text", () => {
    const props = chartTooltipProps();
    expect(props.contentStyle.backgroundColor).toBe("var(--popover)");
    expect(props.contentStyle.border).toBe("1px solid var(--border)");
    expect(props.itemStyle.color).toBe("var(--foreground)");
    expect(props.labelStyle.color).toBe("var(--foreground)");
  });
});
