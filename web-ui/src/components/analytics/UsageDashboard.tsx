import { useCurrentOrg } from "@/hooks/useCurrentOrg";
import { useUsageSummary } from "@/hooks/useUsage";
import { Skeleton } from "@/components/ui/skeleton";

function percentage(current: number, limit: number): number {
  if (limit <= 0) return 0;
  return Math.min((current / limit) * 100, 100);
}

function barColor(pct: number): string {
  if (pct >= 90) return "bg-status-error";
  if (pct >= 80) return "bg-status-warning";
  return "bg-status-info";
}

function badgeClass(pct: number): string {
  if (pct >= 90) {
    return "border border-status-error/20 bg-status-error/15 text-status-error";
  }
  return "border border-status-warning/20 bg-status-warning/15 text-status-warning";
}

function parseQuantity(val: string): number {
  if (val.endsWith("Gi")) return Math.round(parseFloat(val) * 1024);
  if (val.endsWith("Mi")) return Math.round(parseFloat(val));
  return parseFloat(val);
}

interface QuotaBarProps {
  title: string;
  current: number;
  limit: number;
  subtitle?: string;
}

function QuotaBar({ title, current, limit, subtitle }: QuotaBarProps) {
  // limit <= 0 is the sentinel for "unlimited" (API returns -1 for system-tier orgs)
  const isUnlimited = limit <= 0;
  const pct = isUnlimited ? 0 : percentage(current, limit);
  return (
    <div className="rounded-lg border bg-card p-4" data-testid={`quota-${title.toLowerCase().replace(/\s+/g, "-")}`}>
      <div className="flex items-center justify-between">
        <p className="text-sm font-medium text-muted-foreground">{title}</p>
        {!isUnlimited && pct >= 80 && (
          <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${badgeClass(pct)}`}>
            {pct >= 90 ? "Critical" : "Warning"}
          </span>
        )}
      </div>
      <p className="mt-1 text-lg font-semibold tracking-tight">
        {current}{" "}
        <span className="text-sm font-normal text-muted-foreground">
          / {isUnlimited ? "Unlimited" : limit}
        </span>
      </p>
      <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-muted">
        <div
          className={`h-full rounded-full transition-all ${barColor(pct)}`}
          style={{ width: `${pct}%` }}
        />
      </div>
      {subtitle && <p className="mt-1 text-xs text-muted-foreground">{subtitle}</p>}
    </div>
  );
}

export default function UsageDashboard() {
  const orgId = useCurrentOrg();
  const { data, isLoading, error } = useUsageSummary(orgId);

  if (error) {
    return (
      <div className="rounded-lg border bg-card p-4">
        <p className="text-sm text-muted-foreground">Failed to load usage data.</p>
      </div>
    );
  }

  if (isLoading || !data) {
    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="rounded-lg border p-4">
            <Skeleton className="mb-2 h-3 w-24" />
            <Skeleton className="mb-2 h-6 w-16" />
            <Skeleton className="h-2 w-full" />
          </div>
        ))}
      </div>
    );
  }

  return (
    <>
      <div className="rounded-lg border bg-card p-4">
        <h2 className="mb-4 text-sm font-medium text-muted-foreground">Resource Usage</h2>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <QuotaBar
            title="Concurrent Runs"
            current={data.concurrentRuns.current}
            limit={data.concurrentRuns.limit}
          />
          <QuotaBar
            title="Repositories"
            current={data.repos.current}
            limit={data.repos.limit}
          />
          <QuotaBar
            title="Monthly Runs"
            current={data.monthlyRuns.current}
            limit={data.monthlyRuns.limit}
            subtitle={`Since ${new Date(data.monthlyRuns.periodStart).toLocaleDateString()}`}
          />
          <QuotaBar
            title="Monthly Executions"
            current={data.monthlyNodeExecutions.current}
            limit={data.monthlyNodeExecutions.limit}
            subtitle={`Since ${new Date(data.monthlyNodeExecutions.periodStart).toLocaleDateString()}`}
          />
        </div>
      </div>
      <div className="mt-6 rounded-lg border bg-card p-4">
        <h2 className="mb-4 text-sm font-medium text-muted-foreground">
          Cluster Resources
          {data.k8sAggregate && !data.k8sAggregate.enforced && (
            <span className="ml-2 rounded bg-muted px-1.5 py-0.5 text-xs font-normal">
              Monitoring Only
            </span>
          )}
        </h2>
        {data.k8sAggregate ? (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <QuotaBar
              title="CPU (cores)"
              current={parseFloat(data.k8sAggregate.totalCpuAllocated)}
              limit={parseFloat(data.k8sAggregate.maxCpuPerOrg)}
            />
            <QuotaBar
              title="Memory"
              current={parseQuantity(data.k8sAggregate.totalMemoryAllocated)}
              limit={parseQuantity(data.k8sAggregate.maxMemoryPerOrg)}
              subtitle={`${data.k8sAggregate.totalMemoryAllocated} / ${data.k8sAggregate.maxMemoryPerOrg}`}
            />
          </div>
        ) : (
          <div className="rounded-lg border border-status-error/20 bg-status-error/5 p-4">
            <p className="text-sm font-medium text-status-error">Unable to reach cluster metrics</p>
            <p className="mt-1 text-xs text-muted-foreground">
              The API server cannot read node metrics. Check that the metrics-server is running and
              RBAC grants the API server list access to nodes and metrics.k8s.io resources.
            </p>
          </div>
        )}
      </div>
    </>
  );
}
