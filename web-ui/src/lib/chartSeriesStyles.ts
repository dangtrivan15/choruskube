export type ChartSeriesMark = "line" | "bar";

export interface ChartSeriesStyle {
  /** Raw CSS custom-property name (e.g. `--status-success`), never the Tailwind `--color-*` alias. */
  token: string;
  /** SVG stroke-dasharray; "" = solid. Only meaningful for `mark: "line"`. */
  dashArray: string;
  mark: ChartSeriesMark;
  /** Recharts `name` — what the legend and tooltip show. */
  label: string;
}

/**
 * Single source of truth for each analytics chart series' color, dash pattern
 * and legend label — read by the chart components' stroke/fill props and by
 * chart-series-distinguishable.test.ts, which gates every pair's per-theme
 * separation so a future edit here or in index.css can't silently reintroduce
 * a collision. See docs/decisions/2026-09-25---02-chart-series-style-registry.md.
 */
export const CHART_SERIES_STYLES = {
  runTrend: {
    completed: { token: "--status-success", dashArray: "", mark: "line", label: "Completed" },
    failed: { token: "--status-error", dashArray: "2 3", mark: "line", label: "Failed" },
    total: { token: "--chart-reference", dashArray: "6 4", mark: "line", label: "Total" },
  },
  bottleneck: {
    avg: { token: "--chart-4", dashArray: "", mark: "bar", label: "Avg" },
    p95: { token: "--chart-2", dashArray: "", mark: "bar", label: "P95" },
  },
  roadmapThroughput: {
    done: { token: "--status-success", dashArray: "", mark: "bar", label: "Done" },
  },
} as const satisfies Record<string, Record<string, ChartSeriesStyle>>;

/** `var(<token>)`, for a component's stroke/fill prop. */
export function seriesColor(style: ChartSeriesStyle): string {
  return `var(${style.token})`;
}

/**
 * Recharts 3.8.1's legend `plainline` icon checks `'strokeDasharray' in payload`
 * before rendering it, so a solid series must omit the key entirely rather than
 * set it to `undefined` — otherwise the icon renders a literal
 * `stroke-dasharray="undefined"`.
 */
export function seriesDashProps(style: ChartSeriesStyle): { strokeDasharray?: string } {
  return style.dashArray === "" ? {} : { strokeDasharray: style.dashArray };
}
