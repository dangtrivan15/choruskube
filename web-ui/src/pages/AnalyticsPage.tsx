import { useState } from "react";
import {
  useAnalyticsOverview,
  useRunTrend,
  useTemplateAnalytics,
  useNodeAnalytics,
  useBottlenecks,
  useProposalStatusCounts,
  useProposalThroughput,
} from "@/hooks/useAnalytics";
import { Skeleton } from "@/components/ui/skeleton";
import PeriodSelector from "@/components/analytics/PeriodSelector";
import StatCard from "@/components/analytics/StatCard";
import RunTrendChart from "@/components/analytics/RunTrendChart";
import BottleneckChart from "@/components/analytics/BottleneckChart";
import TemplateTable from "@/components/analytics/TemplateTable";
import NodeTable from "@/components/analytics/NodeTable";
import ProposalThroughputChart from "@/components/analytics/ProposalThroughputChart";
import UsageDashboard from "@/components/analytics/UsageDashboard";
import PageHeader from "@/components/layout/PageHeader";
import PageShell from "@/components/layout/PageShell";

function formatDuration(seconds: number | null): string {
  if (seconds == null) return "-";
  if (seconds < 60) return `${seconds.toFixed(0)}s`;
  if (seconds < 3600) return `${(seconds / 60).toFixed(1)}m`;
  return `${(seconds / 3600).toFixed(1)}h`;
}

const STATUS_LABELS: Record<string, string> = {
  backlog: "Backlog",
  in_progress: "In Progress",
  rolled_out: "Rolled Out",
};

export default function AnalyticsPage() {
  const [period, setPeriod] = useState("30d");

  const { data: overview, isLoading: overviewLoading } = useAnalyticsOverview(period);
  const { data: trend, isLoading: trendLoading } = useRunTrend(period);
  const { data: templates, isLoading: templatesLoading } = useTemplateAnalytics(period);
  const { data: nodes, isLoading: nodesLoading } = useNodeAnalytics(period);
  const { data: bottlenecks, isLoading: bottlenecksLoading } = useBottlenecks(period);
  const { data: statusCounts, isLoading: statusCountsLoading } = useProposalStatusCounts();
  const { data: throughput, isLoading: throughputLoading } = useProposalThroughput(period);

  return (
    <PageShell spacing="relaxed">
      {/* Header */}
      <PageHeader title="Analytics">
        <PeriodSelector value={period} onChange={setPeriod} />
      </PageHeader>

      {/* Resource Usage */}
      <UsageDashboard />

      {/* Overview Stats */}
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4 lg:grid-cols-7">
        {overviewLoading ? (
          Array.from({ length: 7 }).map((_, i) => (
            <div key={i} className="rounded-lg border p-4">
              <Skeleton className="mb-2 h-3 w-20" />
              <Skeleton className="h-7 w-16" />
            </div>
          ))
        ) : overview ? (
          <>
            <StatCard title="Total Runs" value={overview.totalRuns} />
            <StatCard title="Completed" value={overview.completedRuns} />
            <StatCard title="Failed" value={overview.failedRuns} />
            <StatCard title="Success Rate" value={`${overview.successRate.toFixed(1)}%`} />
            <StatCard
              title="Avg Duration"
              value={formatDuration(overview.avgDurationSeconds)}
            />
            <StatCard
              title="P50 Duration"
              value={formatDuration(overview.p50DurationSeconds)}
            />
            <StatCard
              title="P95 Duration"
              value={formatDuration(overview.p95DurationSeconds)}
            />
          </>
        ) : null}
      </div>

      {/* Run Trend Chart */}
      <div className="rounded-lg border bg-card p-4">
        <h2 className="mb-4 text-sm font-medium text-muted-foreground">Run Trend</h2>
        {trendLoading ? (
          <Skeleton className="h-64 w-full" />
        ) : trend ? (
          <RunTrendChart points={trend.points} />
        ) : null}
      </div>

      {/* Two-column: Templates & Bottlenecks */}
      <div className="grid gap-6 lg:grid-cols-2">
        {/* Template Analytics */}
        {/*
          min-w-0 lets this grid item shrink below its content's min-content
          width so the inner overflow-x-auto wrapper on TemplateTable can
          actually take effect. Without it, the table's intrinsic width
          inflates the grid track and the page scrolls horizontally.
        */}
        <div className="min-w-0 rounded-lg border bg-card p-4">
          <h2 className="mb-4 text-sm font-medium text-muted-foreground">Templates</h2>
          {templatesLoading ? (
            <Skeleton className="h-32 w-full" />
          ) : templates ? (
            <TemplateTable templates={templates.templates} />
          ) : null}
        </div>

        {/* Bottleneck Chart */}
        {/*
          min-w-0 lets Recharts' ResponsiveContainer measure this grid item
          at the column's 1fr share instead of being inflated by the chart's
          own min-content width.
        */}
        <div className="min-w-0 rounded-lg border bg-card p-4">
          <h2 className="mb-4 text-sm font-medium text-muted-foreground">Bottlenecks</h2>
          {bottlenecksLoading ? (
            <Skeleton className="h-72 w-full" />
          ) : bottlenecks ? (
            <BottleneckChart bottlenecks={bottlenecks.bottlenecks} />
          ) : null}
        </div>
      </div>

      {/* Node Analytics */}
      <div className="rounded-lg border bg-card p-4">
        <h2 className="mb-4 text-sm font-medium text-muted-foreground">Node Executions</h2>
        {nodesLoading ? (
          <Skeleton className="h-32 w-full" />
        ) : nodes ? (
          <NodeTable nodes={nodes.nodes} />
        ) : null}
      </div>

      {/* Roadmap Analytics */}
      <div className="rounded-lg border bg-card p-4">
        <h2 className="mb-4 text-sm font-medium text-muted-foreground">Roadmap</h2>
        <div className="grid gap-6 lg:grid-cols-2">
          {/* Status Counts */}
          <div className="min-w-0">
            <h3 className="mb-3 text-xs font-medium text-muted-foreground">Proposals by Status</h3>
            {statusCountsLoading ? (
              <div className="grid grid-cols-3 gap-3">
                {Array.from({ length: 3 }).map((_, i) => (
                  <div key={i} className="rounded-lg border p-3">
                    <Skeleton className="mb-2 h-3 w-16" />
                    <Skeleton className="h-6 w-10" />
                  </div>
                ))}
              </div>
            ) : statusCounts ? (
              <div className="grid grid-cols-3 gap-3">
                {(["backlog", "in_progress", "rolled_out"] as const).map((status) => {
                  const entry = statusCounts.statuses.find((s) => s.status === status);
                  return (
                    <StatCard
                      key={status}
                      title={STATUS_LABELS[status]}
                      value={entry?.count ?? 0}
                    />
                  );
                })}
              </div>
            ) : null}
          </div>

          {/* Throughput Chart */}
          <div className="min-w-0">
            <h3 className="mb-3 text-xs font-medium text-muted-foreground">Rollout Throughput</h3>
            {throughputLoading ? (
              <Skeleton className="h-48 w-full" />
            ) : throughput ? (
              <ProposalThroughputChart points={throughput.points} />
            ) : null}
          </div>
        </div>
      </div>
    </PageShell>
  );
}
