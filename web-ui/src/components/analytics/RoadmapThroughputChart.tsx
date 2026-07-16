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

interface RoadmapThroughputChartProps {
  points: RoadmapThroughputPoint[];
}

// NOTE: Recharts legend/tooltip swatches may not resolve CSS var() — see RunTrendChart.tsx
export default function RoadmapThroughputChart({ points }: RoadmapThroughputChartProps) {
  if (points.length === 0) {
    return (
      <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
        No tasks completed in this period
      </div>
    );
  }

  return (
    <div className="h-48">
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
              backgroundColor: "hsl(var(--card))",
              border: "1px solid hsl(var(--border))",
              borderRadius: "0.5rem",
              fontSize: "0.875rem",
            }}
          />
          <Bar dataKey="count" name="Done" fill="var(--status-success)" radius={[4, 4, 0, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
