import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
} from "recharts";
import type { BottleneckNode } from "@/lib/types";

interface BottleneckChartProps {
  bottlenecks: BottleneckNode[];
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds.toFixed(0)}s`;
  if (seconds < 3600) return `${(seconds / 60).toFixed(1)}m`;
  return `${(seconds / 3600).toFixed(1)}h`;
}

// NOTE: Recharts legend/tooltip swatches may not resolve CSS var() — see RunTrendChart.tsx
export default function BottleneckChart({ bottlenecks }: BottleneckChartProps) {
  if (bottlenecks.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">
        No bottleneck data for this period
      </div>
    );
  }

  // Show top 10 by avg duration
  const data = bottlenecks.slice(0, 10);

  return (
    <div className="h-72 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 8, left: 4, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" className="stroke-border" horizontal={false} />
          <XAxis
            type="number"
            tick={{ fontSize: 12 }}
            tickFormatter={formatDuration}
            className="text-muted-foreground"
          />
          <YAxis
            type="category"
            dataKey="label"
            tick={{ fontSize: 11 }}
            width={96}
            className="text-muted-foreground"
          />
          <Tooltip
            contentStyle={{
              backgroundColor: "var(--card)",
              border: "1px solid var(--border)",
              borderRadius: "0.5rem",
              fontSize: "0.875rem",
            }}
            formatter={(value: unknown) => formatDuration(Number(value))}
          />
          <Legend wrapperStyle={{ fontSize: "0.75rem" }} />
          <Bar dataKey="avgDurationSeconds" name="Avg" fill="var(--status-info)" radius={[0, 4, 4, 0]} />
          <Bar dataKey="p95DurationSeconds" name="P95" fill="var(--status-warning)" radius={[0, 4, 4, 0]} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
