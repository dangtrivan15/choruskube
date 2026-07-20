import { useState } from "react";
import { Link } from "react-router";
import { ExternalLink, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import TaskRunHistoryList from "@/components/roadmap/TaskRunHistoryList";
import { useTaskRuns } from "@/hooks/useTaskRuns";
import { useCreateDependency, useDeleteDependency } from "@/hooks/useDependencies";
import type {
  EpicResponse,
  StoryResponse,
  TaskResponse,
  ExternalBlockerRef,
  DependencyEdgeResponse,
} from "@/lib/types";
import type { RoadmapItemType } from "./RoadmapGraphNode";

export type RoadmapDetailItem =
  | { itemType: "epic"; item: EpicResponse }
  | { itemType: "story"; item: StoryResponse }
  | { itemType: "task"; item: TaskResponse };

/** A Story or Task in the current Epic, in the shape the "add blocker" picker needs. */
export interface BlockableItemRef {
  id: string;
  itemType: "story" | "task";
  title: string;
}

interface Props {
  detail: RoadmapDetailItem;
  epicId: string;
  /** Every intra-Epic "blocking" dependency edge (RoadmapGraphSnapshot.dependencies). */
  dependencies: DependencyEdgeResponse[];
  /** Every Story/Task in the Epic, for the "add blocker" picker (Epics can't participate — BlockableItemType has no "epic" variant). */
  blockableItems: BlockableItemRef[];
  /**
   * The Epic graph's full external-blockers list (RoadmapGraphSnapshot.externalBlockers).
   *
   * The backend does not currently record which internal item a given external
   * blocker's dependency edge connects to (DefaultRoadmapGraphService discards
   * that correlation once it classifies a row as "external") — there is no
   * per-item filter available, so every external blocker for the Epic is
   * surfaced here as context regardless of which node is selected, rather
   * than silently pretending a precise per-node filter exists.
   */
  externalBlockers: ExternalBlockerRef[];
}

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

function itemTypeLabel(itemType: RoadmapItemType): string {
  switch (itemType) {
    case "epic":
      return "Epic";
    case "story":
      return "Story";
    case "task":
      return "Task";
  }
}

function ExternalBlockersSection({ blockers }: { blockers: ExternalBlockerRef[] }) {
  if (blockers.length === 0) return null;
  return (
    <div data-testid="roadmap-external-blockers" className="pt-3 border-t space-y-2">
      <h3 className="text-sm font-medium text-muted-foreground">External blockers</h3>
      <ul className="flex flex-wrap gap-2">
        {blockers.map((blocker) => (
          <li key={`${blocker.itemType}-${blocker.itemId}`}>
            <Badge
              variant="outline"
              data-testid="roadmap-external-blocker-badge"
              className="gap-1"
              title={`${blocker.title} (${blocker.epicTitle})`}
            >
              {blocker.title}
              <span className="text-muted-foreground">in {blocker.epicTitle}</span>
            </Badge>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * "Blocked by" management for a Story/Task node — lists existing blocking
 * edges (with a remove action) and a picker + button to add a new one.
 * Epics are excluded: BlockableItemType only has story/task variants, so an
 * Epic node can neither block nor be blocked.
 */
function BlockingDependenciesSection({
  itemType,
  itemId,
  epicId,
  dependencies,
  blockableItems,
}: {
  itemType: "story" | "task";
  itemId: string;
  epicId: string;
  dependencies: DependencyEdgeResponse[];
  blockableItems: BlockableItemRef[];
}) {
  const createDependency = useCreateDependency(epicId);
  const deleteDependency = useDeleteDependency(epicId);
  const [selectedBlockerId, setSelectedBlockerId] = useState("");

  const blockedByEdges = dependencies.filter((d) => d.blockedItemId === itemId);
  const pickerOptions = blockableItems.filter((i) => i.id !== itemId);

  function titleFor(id: string): string {
    return blockableItems.find((i) => i.id === id)?.title ?? id;
  }

  function handleAdd() {
    const target = pickerOptions.find((i) => i.id === selectedBlockerId);
    if (!target) return;
    createDependency.mutate(
      {
        blockingItemType: target.itemType,
        blockingItemId: target.id,
        blockedItemType: itemType,
        blockedItemId: itemId,
      },
      { onSuccess: () => setSelectedBlockerId("") },
    );
  }

  return (
    <div data-testid="roadmap-blocking-dependencies" className="pt-3 border-t space-y-2">
      <h3 className="text-sm font-medium text-muted-foreground">Blocked by</h3>
      {blockedByEdges.length === 0 ? (
        <p className="text-sm text-muted-foreground">No blocking dependencies.</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {blockedByEdges.map((edge) => (
            <li key={edge.id}>
              <Badge variant="outline" data-testid="roadmap-blocking-dependency-badge" className="gap-1">
                {titleFor(edge.blockingItemId)}
                <button
                  type="button"
                  aria-label="Remove dependency"
                  data-testid="roadmap-blocking-dependency-remove"
                  onClick={() => deleteDependency.mutate(edge.id)}
                  className="ml-1 text-muted-foreground hover:text-foreground"
                >
                  <X className="size-3" />
                </button>
              </Badge>
            </li>
          ))}
        </ul>
      )}

      {pickerOptions.length > 0 && (
        <div className="flex items-center gap-2">
          <select
            data-testid="roadmap-add-blocker-select"
            value={selectedBlockerId}
            onChange={(e) => setSelectedBlockerId(e.target.value)}
            className="h-8 rounded-md border bg-background px-2 text-sm"
          >
            <option value="">Select an item…</option>
            {pickerOptions.map((opt) => (
              <option key={opt.id} value={opt.id}>
                {opt.title} ({opt.itemType})
              </option>
            ))}
          </select>
          <Button
            type="button"
            size="sm"
            data-testid="roadmap-add-blocker-submit"
            disabled={!selectedBlockerId || createDependency.isPending}
            onClick={handleAdd}
          >
            Add blocker
          </Button>
        </div>
      )}
    </div>
  );
}

/**
 * Roadmap Graph View detail panel — shown when a node (Epic/Story/Task) is
 * clicked in RoadmapGraph. Mirrors RunMonitorPage's DetailPanel/RunMetaPanel
 * split conceptually, but a single component covers all three item types
 * since they share the same status + description shape.
 */
export default function RoadmapGraphDetailPanel({
  detail,
  epicId,
  dependencies,
  blockableItems,
  externalBlockers,
}: Props) {
  const { itemType, item } = detail;
  const taskId = itemType === "task" ? item.id : undefined;
  const { data: runsPage, isLoading: runsLoading } = useTaskRuns(taskId);

  return (
    <div data-testid="roadmap-detail-panel" className="p-4 space-y-4">
      <div className="space-y-2">
        <span
          data-testid="roadmap-detail-item-type"
          className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
        >
          {itemTypeLabel(itemType)}
        </span>
        <h2 data-testid="roadmap-detail-title" className="text-lg font-semibold break-words">
          {item.title}
        </h2>
        <div data-testid="roadmap-detail-status">{statusBadge(item.status)}</div>
      </div>

      <div data-testid="roadmap-detail-description">
        <h3 className="text-sm font-medium text-muted-foreground mb-2">Description</h3>
        <MarkdownViewer content={item.description} maxHeight="max-h-48" />
      </div>

      {itemType === "task" && (
        <div className="pt-3 border-t">
          <h3 className="text-sm font-medium text-muted-foreground mb-2">Run history</h3>
          <TaskRunHistoryList runs={runsPage?.content} isLoading={runsLoading} />
          <Link
            to={`/tasks/${item.id}`}
            className="mt-2 inline-flex items-center gap-1 text-xs text-primary hover:underline"
          >
            <ExternalLink className="size-3" />
            Open Task detail
          </Link>
        </div>
      )}

      {itemType !== "epic" && (
        <BlockingDependenciesSection
          itemType={itemType}
          itemId={item.id}
          epicId={epicId}
          dependencies={dependencies}
          blockableItems={blockableItems}
        />
      )}

      <ExternalBlockersSection blockers={externalBlockers} />
    </div>
  );
}
