import { useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { GitBranch, Layers } from "lucide-react";
import { useAllTasks } from "@/hooks/useTasks";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { PaginationParams } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import TruncatedText from "@/components/ui/TruncatedText";
import Pagination from "@/components/ui/Pagination";
import RunStatusBadge from "@/components/runs/RunStatusBadge";
import RoadmapViewControls from "@/components/roadmap/RoadmapViewControls";
import PageHeader from "@/components/layout/PageHeader";

const PAGE_SIZE = 20;

/**
 * A Task's own `status`, rendered the way every other Task row in the app renders it. Task has no
 * board `stage` distinct from its status (see TaskBoardPage), so this is deliberately not
 * `StageBadge`: that component's vocabulary is backlog/in progress/rolled out, and a Task's
 * terminal state is `done`.
 */
function statusBadge(status: "backlog" | "in_progress" | "done") {
  switch (status) {
    case "backlog":
      return <Badge variant="outline">backlog</Badge>;
    case "in_progress":
      return <Badge variant="secondary">in progress</Badge>;
    case "done":
      return <Badge variant="default">done</Badge>;
  }
}

/**
 * Task list — every Task in the org, the Task-level counterpart of `RoadmapPage`'s Epic list and
 * the flat reading of the same items the Task Board arranges by status. Backed by the same
 * org-wide `GET /tasks` listing the board already uses, so this page adds a view, not an endpoint.
 *
 * No "Ready to start" filter and no readiness badge: `useAllTasks` has no readiness parameter, and
 * the listing endpoint's shared mapper leaves `TaskResponse.readiness` null, so a badge here could
 * never show a value — the same reason `TaskBoardCard` omits one.
 */
export default function TaskListPage() {
  const [page, setPage] = useState(0);
  const pagination: PaginationParams = { page, size: PAGE_SIZE };
  const { data: pageData, isLoading } = useAllTasks(undefined, pagination);
  const tasks = pageData?.content;
  useRoadmapSubscription();

  return (
    <div className="flex h-full min-w-0 flex-col p-4 md:p-6">
      <PageHeader title="Tasks" data-testid="task-list-heading">
        <RoadmapViewControls level="task" view="list" />
      </PageHeader>

      <div data-testid="task-list" className="mt-4 flex-1 overflow-y-auto">
        {isLoading &&
          Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="p-3 border-b">
              <Skeleton className="h-4 w-3/4 mb-2" />
              <Skeleton className="h-3 w-1/3" />
            </div>
          ))}
        {tasks?.map((task) => {
          const sp = task.softwareProject;
          const Icon = sp.type === "repo_group" ? Layers : GitBranch;
          return (
            <div
              key={task.id}
              data-testid="task-item"
              className="flex items-center justify-between gap-4 p-3 border-b transition-colors hover:bg-muted/50"
            >
              <Link to={`/tasks/${task.id}`} className="min-w-0 flex-1">
                <TruncatedText as="div" className="font-medium text-sm">
                  {task.title}
                </TruncatedText>
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  {statusBadge(task.status)}
                  {task.latestRunStatus && <RunStatusBadge status={task.latestRunStatus} />}
                  <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                    <Icon className="size-3" />
                    {sp.name}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {formatDistanceToNow(new Date(task.createdAt), { addSuffix: true })}
                  </span>
                </div>
              </Link>
            </div>
          );
        })}
        {tasks && tasks.length === 0 && (
          <div data-testid="task-list-empty" className="p-6 text-center text-muted-foreground text-sm">
            No tasks yet. Open a Story to create one.
          </div>
        )}
      </div>

      {pageData && (
        <div className="border-t p-2">
          <Pagination page={pageData.number} totalPages={pageData.totalPages} onPageChange={setPage} />
        </div>
      )}
    </div>
  );
}
