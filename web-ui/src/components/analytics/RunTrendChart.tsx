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

interface RunTrendChartProps {
  points: RunTrendPoint[];
}

/**
 * NOTE: Recharts receives CSS `var()` references for stroke/fill attributes.
 * SVG presentation attributes resolve `var()` correctly, but Recharts legend
 * and tooltip color swatches render inline `<li style="color: ...">` which
 * may pass the raw `var(...)` string. Visually verify legend/tooltip swatches
 * after theme changes. If broken, resolve colors via getComputedStyle at render.
 */
export default function RunTrendChart({ points }: RunTrendChartProps) {
  if (points.length === 0) {
    return (
      <div className="flex h-64 items-center justify-center text-sm text-muted-foreground">
        No run data for this period
      </div>
    );
  }

  return (
    <div className="h-64">
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
          />
          <Legend wrapperStyle={{ fontSize: "0.75rem" }} />
          <Area
            type="monotone"
            dataKey="total"
            name="Total"
            stroke="var(--foreground)"
            fill="var(--foreground)"
            fillOpacity={0.05}
            strokeWidth={2}
          />
          <Area
            type="monotone"
            dataKey="completed"
            name="Completed"
            stroke="var(--status-success)"
            fill="var(--status-success)"
            fillOpacity={0.1}
            strokeWidth={2}
          />
          <Area
            type="monotone"
            dataKey="failed"
            name="Failed"
            stroke="var(--status-error)"
            fill="var(--status-error)"
            fillOpacity={0.1}
            strokeWidth={2}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
