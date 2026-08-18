import { useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { Plus, GitBranch, Layers, LayoutGrid, Network, GanttChart, Milestone as MilestoneIcon } from "lucide-react";
import Authorized from "@/components/Authorized";
import { useEpics } from "@/hooks/useEpics";
import { useMilestones } from "@/hooks/useMilestones";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { SortParam, PaginationParams, Priority } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import TruncatedText from "@/components/ui/TruncatedText";
import Pagination from "@/components/ui/Pagination";
import SortDropdown from "@/components/ui/SortDropdown";
import { cn } from "@/lib/utils";
import CreateEpicDialog from "@/components/roadmap/CreateEpicDialog";
import RoadmapReadyToggle from "@/components/roadmap/RoadmapReadyToggle";
import PriorityFilter from "@/components/roadmap/PriorityFilter";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import StageBadge from "@/components/roadmap/StageBadge";
import MilestoneBadge from "@/components/roadmap/MilestoneBadge";
import PageHeader from "@/components/layout/PageHeader";

const SORT_OPTIONS = [
  { label: "Newest first", field: "createdAt", direction: "desc" as const },
  { label: "Oldest first", field: "createdAt", direction: "asc" as const },
  { label: "Title A-Z", field: "title", direction: "asc" as const },
  { label: "Title Z-A", field: "title", direction: "desc" as const },
  // Server-side `?sort=priority,{asc|desc}`. Descending is High→Low because the
  // backend enum orders low < medium < high.
  { label: "Priority (High→Low)", field: "priority", direction: "desc" as const },
  { label: "Priority (Low→High)", field: "priority", direction: "asc" as const },
];

interface MilestoneFilterProps {
  /** The active Milestone filter (an id), or `undefined` for "All" (no filtering). */
  value: string | undefined;
  onChange: (value: string | undefined) => void;
}

/**
 * Roadmap toolbar "milestone" filter — an All + one chip per Milestone in the org, styled
 * consistently with `PriorityFilter` (Decision 4/3.5 of the "Group Epics under a named
 * Milestone / Release" feature). Unlike `PriorityFilter`'s fixed three-value enum, the option
 * set is dynamic (`useMilestones()`, unscoped — the Roadmap Epic list spans every software
 * project), so this stays inline here rather than a reusable component with a bounded value
 * union.
 */
function MilestoneFilter({ value, onChange }: MilestoneFilterProps) {
  const { data } = useMilestones();
  const milestones = data?.content ?? [];

  return (
    <div
      data-testid="milestone-filter"
      role="group"
      aria-label="Filter by milestone"
      className="inline-flex h-8 shrink-0 items-center gap-0.5 overflow-x-auto rounded-lg border border-border bg-background p-0.5 text-sm max-w-64"
    >
      <button
        type="button"
        data-testid="milestone-filter-all"
        aria-pressed={value === undefined}
        onClick={() => onChange(undefined)}
        className={cn(
          "inline-flex h-7 shrink-0 items-center rounded-md px-2.5 font-medium transition-colors outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/50",
          value === undefined
            ? "bg-primary text-primary-foreground"
            : "text-muted-foreground hover:text-foreground",
        )}
      >
        All
      </button>
      {milestones.map((m) => {
        const active = value === m.id;
        return (
          <button
            key={m.id}
            type="button"
            data-testid={`milestone-filter-${m.id}`}
            aria-pressed={active}
            title={m.name}
            onClick={() => onChange(m.id)}
            className={cn(
              "inline-flex h-7 max-w-32 shrink-0 items-center truncate rounded-md px-2.5 font-medium transition-colors outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/50",
              active
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            {m.name}
          </button>
        );
      })}
    </div>
  );
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
  const [readyOnly, setReadyOnly] = useState(false);
  const [priority, setPriority] = useState<Priority | undefined>(undefined);
  const [milestoneFilter, setMilestoneFilter] = useState<string | undefined>(undefined);

  const pagination: PaginationParams = { page, size: 20, sort };
  const { data: pageData, isLoading } = useEpics(undefined, pagination, readyOnly, priority, milestoneFilter);
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
        <Link
          to="/roadmap/timeline"
          data-testid="roadmap-timeline-view-link"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <GanttChart className="size-4" />
          Timeline view
        </Link>
        <Link
          to="/roadmap/milestones"
          data-testid="roadmap-milestones-link"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <MilestoneIcon className="size-4" />
          Milestones
        </Link>
        <RoadmapReadyToggle checked={readyOnly} onChange={setReadyOnly} />
        <PriorityFilter value={priority} onChange={setPriority} />
        <MilestoneFilter value={milestoneFilter} onChange={setMilestoneFilter} />
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
                  <StageBadge stage={epic.stage} data-testid="epic-stage-badge" />
                  <PriorityBadge priority={epic.priority} size="compact" data-testid="epic-priority-badge" />
                  <MilestoneBadge milestone={epic.milestone} size="compact" data-testid="epic-milestone-badge" />
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
          <div data-testid="epic-list-empty" className="p-6 text-center text-muted-foreground text-sm">
            {readyOnly && priority ? (
              <>
                No epics match the current filters. Try turning off the &ldquo;Ready to start&rdquo; filter or
                clearing the priority filter.
              </>
            ) : readyOnly ? (
              <>No epics currently have ready work. Try turning off the &ldquo;Ready to start&rdquo; filter.</>
            ) : priority ? (
              <>No epics match the selected priority. Try clearing the priority filter.</>
            ) : (
              <>No epics yet. Click &ldquo;New Epic&rdquo; to create one.</>
            )}
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
