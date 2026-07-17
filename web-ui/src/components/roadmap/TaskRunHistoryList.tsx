import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { ExternalLink } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import type { RunSummary } from "@/lib/types";

interface Props {
  runs: RunSummary[] | undefined;
  isLoading: boolean;
}

/**
 * Renders a Task's full run history (Decision 1 — every run a Task ever
 * launched stays queryable via `task_id`, newest first), not just the
 * latest one.
 */
export default function TaskRunHistoryList({ runs, isLoading }: Props) {
  if (isLoading) {
    return (
      <div className="space-y-2">
        {Array.from({ length: 2 }).map((_, i) => (
          <Skeleton key={i} className="h-12 w-full" />
        ))}
      </div>
    );
  }

  if (!runs || runs.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No runs yet. Start the task to launch the first one.
      </p>
    );
  }

  return (
    <ul data-testid="task-run-history-list" className="divide-y rounded-md border">
      {runs.map((run) => (
        <li key={run.id} data-testid="task-run-history-item" className="flex items-center justify-between gap-4 p-3">
          <div className="min-w-0">
            <Link
              to={`/runs/${run.id}`}
              className="inline-flex items-center gap-1.5 text-sm font-medium text-primary hover:underline"
            >
              <ExternalLink className="size-3.5" />
              {run.name ?? run.templateName}
            </Link>
            <div className="mt-1 text-xs text-muted-foreground">
              {formatDistanceToNow(new Date(run.createdAt), { addSuffix: true })}
            </div>
          </div>
          <Badge variant="outline" data-testid="task-run-history-status">
            {run.status}
          </Badge>
        </li>
      ))}
    </ul>
  );
}
