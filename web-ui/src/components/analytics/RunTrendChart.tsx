import {
  ResponsiveContainer,
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
} from "recharts";
import type { RunTrendPoint } from "@/lib/types";
import { CHART_SERIES_STYLES, seriesColor, seriesDashProps } from "@/lib/chartSeriesStyles";

interface RunTrendChartProps {
  points: RunTrendPoint[];
}

const { completed, failed, total } = CHART_SERIES_STYLES.runTrend;

export default function RunTrendChart({ points }: RunTrendChartProps) {
  if (points.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">
        No run data for this period
      </div>
    );
  }

  return (
    <div className="h-64" data-testid="run-trend-chart">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={points} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" className="stroke-border" />
          <XAxis
            dataKey="date"
            tick={{ fontSize: 12 }}
            tickFormatter={(v: string) => v.slice(5)}
            className="text-muted-foreground"
          />
          <YAxis allowDecimals={false} tick={{ fontSize: 12 }} className="text-muted-foreground" />
          <Tooltip
            contentStyle={{
              backgroundColor: "var(--card)",
              border: "1px solid var(--border)",
              borderRadius: "0.5rem",
              fontSize: "0.875rem",
            }}
            itemStyle={{ color: "var(--foreground)" }}
          />
          {/* iconSize 24: Recharts scales its 32-unit legend icon viewBox to iconSize,
              so the default 14px shrinks Failed's dotted pattern below legibility. */}
          <Legend
            wrapperStyle={{ fontSize: "0.75rem" }}
            labelStyle={{ color: "var(--foreground)" }}
            iconSize={24}
          />
          <Area
            type="monotone"
            dataKey="completed"
            name={completed.label}
            className="series-completed"
            stroke={seriesColor(completed)}
            fill={seriesColor(completed)}
            fillOpacity={0.1}
            strokeWidth={2}
            legendType="plainline"
            {...seriesDashProps(completed)}
          />
          <Area
            type="monotone"
            dataKey="failed"
            name={failed.label}
            className="series-failed"
            stroke={seriesColor(failed)}
            fill={seriesColor(failed)}
            fillOpacity={0.1}
            strokeWidth={2}
            legendType="plainline"
            {...seriesDashProps(failed)}
          />
          {/* Total is unfilled and painted last (on top) so it stays visible when
              every run succeeds and its value coincides with Completed's. */}
          <Area
            type="monotone"
            dataKey="total"
            name={total.label}
            className="series-total"
            stroke={seriesColor(total)}
            fill="none"
            strokeWidth={2}
            legendType="plainline"
            {...seriesDashProps(total)}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
