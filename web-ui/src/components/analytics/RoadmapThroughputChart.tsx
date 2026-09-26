import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
} from "recharts";
import type { RoadmapThroughputPoint } from "@/lib/types";
import { CHART_SERIES_STYLES, seriesColor } from "@/lib/chartSeriesStyles";

interface RoadmapThroughputChartProps {
  points: RoadmapThroughputPoint[];
}

const { done } = CHART_SERIES_STYLES.roadmapThroughput;

// Series color comes from @/lib/chartSeriesStyles, so its contrast is gated by
// chart-series-distinguishable.test.ts.
export default function RoadmapThroughputChart({ points }: RoadmapThroughputChartProps) {
  if (points.length === 0) {
    return (
      <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
        No tasks completed in this period
      </div>
    );
  }

  return (
    <div className="h-48" data-testid="roadmap-throughput-chart">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={points} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
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
          <Bar dataKey="count" name={done.label} className="series-done" fill={seriesColor(done)} radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
