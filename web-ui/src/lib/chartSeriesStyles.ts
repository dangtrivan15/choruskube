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
 * Single source of every analytics chart series' color, dash and label; each entry's per-theme
 * separation is gated by chart-series-distinguishable.test.ts, so add new series here, not inline.
 * See docs/decisions/2026-09-25---02-chart-series-style-registry.md.
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
 * Omits the key for a solid series: Recharts' legend icon tests `'strokeDasharray' in payload`,
 * so an explicit `undefined` would render a literal `stroke-dasharray="undefined"`.
 */
export function seriesDashProps(style: ChartSeriesStyle): { strokeDasharray?: string } {
  return style.dashArray === "" ? {} : { strokeDasharray: style.dashArray };
}
