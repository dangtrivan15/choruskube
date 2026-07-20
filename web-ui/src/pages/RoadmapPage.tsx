import { useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { Plus, GitBranch, Layers, LayoutGrid, Network } from "lucide-react";
import Authorized from "@/components/Authorized";
import { useEpics } from "@/hooks/useEpics";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { SortParam, PaginationParams } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import TruncatedText from "@/components/ui/TruncatedText";
import Pagination from "@/components/ui/Pagination";
import SortDropdown from "@/components/ui/SortDropdown";
import CreateEpicDialog from "@/components/roadmap/CreateEpicDialog";
import PageHeader from "@/components/layout/PageHeader";

const SORT_OPTIONS = [
  { label: "Newest first", field: "createdAt", direction: "desc" as const },
  { label: "Oldest first", field: "createdAt", direction: "asc" as const },
  { label: "Title A-Z", field: "title", direction: "asc" as const },
  { label: "Title Z-A", field: "title", direction: "desc" as const },
];

function statusBadge(status: string) {
  switch (status) {
    case "backlog":
      return <Badge variant="outline">backlog</Badge>;
    case "in_progress":
      return <Badge variant="secondary">in progress</Badge>;
    case "done":
      return <Badge variant="default">done</Badge>;
    default:
      return <Badge variant="outline">{status}</Badge>;
  }
}

/**
 * Roadmap — the Epic list. Drilling into an Epic (Story list) or a Story
 * (Task list) navigates to its own route; there is no master-detail split
 * here anymore, since the hierarchy has three more levels below this one.
 */
export default function RoadmapPage() {
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<SortParam | null>(null);
  const [createOpen, setCreateOpen] = useState(false);

  const pagination: PaginationParams = { page, size: 20, sort };
  const { data: pageData, isLoading } = useEpics(undefined, pagination);
  const epics = pageData?.content;
  useRoadmapSubscription();

  return (
    <div className="flex h-full min-w-0 flex-col p-4 md:p-6">
      <PageHeader title="Roadmap" data-testid="roadmap-heading">
        <Link
          to="/roadmap/board"
          data-testid="roadmap-board-view-link"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <LayoutGrid className="size-4" />
          Board view
        </Link>
        <SortDropdown options={SORT_OPTIONS} currentSort={sort} onSort={setSort} />
        <Authorized require="canOperate">
          <Button data-testid="new-epic-button" size="sm" onClick={() => setCreateOpen(true)}>
            <Plus className="size-4" />
            New Epic
          </Button>
        </Authorized>
      </PageHeader>

      <div data-testid="epic-list" className="mt-4 flex-1 overflow-y-auto">
        {isLoading &&
          Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="p-3 border-b">
              <Skeleton className="h-4 w-3/4 mb-2" />
              <Skeleton className="h-3 w-1/3" />
            </div>
          ))}
        {epics?.map((epic) => {
          const sp = epic.softwareProject;
          const Icon = sp.type === "repo_group" ? Layers : GitBranch;
          return (
            <div
              key={epic.id}
              data-testid="epic-item"
              className="flex items-center justify-between gap-4 p-3 border-b transition-colors hover:bg-muted/50"
            >
              <Link to={`/roadmap/epics/${epic.id}`} className="min-w-0 flex-1">
                <TruncatedText as="div" className="font-medium text-sm">
                  {epic.title}
                </TruncatedText>
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  {statusBadge(epic.status)}
                  <span
                    data-testid="epic-progress"
                    className="text-xs text-muted-foreground"
                  >
                    {epic.progress.doneTasks}/{epic.progress.totalTasks} tasks done
                  </span>
                  <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                    <Icon className="size-3" />
                    {sp.name}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {formatDistanceToNow(new Date(epic.createdAt), { addSuffix: true })}
                  </span>
                </div>
              </Link>
              <Link
                to={`/roadmap/epics/${epic.id}/graph`}
                data-testid="epic-graph-link"
                aria-label={`View graph for ${epic.title}`}
                className="inline-flex shrink-0 items-center gap-1 rounded-md border px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
              >
                <Network className="size-3.5" />
                Graph
              </Link>
            </div>
          );
        })}
        {epics && epics.length === 0 && (
          <div className="p-6 text-center text-muted-foreground text-sm">
            No epics yet. Click &ldquo;New Epic&rdquo; to create one.
          </div>
        )}
      </div>

      {pageData && (
        <div className="border-t p-2">
          <Pagination page={pageData.number} totalPages={pageData.totalPages} onPageChange={setPage} />
        </div>
      )}

      <CreateEpicDialog open={createOpen} onOpenChange={setCreateOpen} />
    </div>
  );
}
