import { useState } from "react";
import { Link } from "react-router";
import { ExternalLink, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import TaskRunHistoryList from "@/components/roadmap/TaskRunHistoryList";
import BlockingChainSection from "@/components/roadmap/BlockingChainSection";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import { useCreateDependency, useDeleteDependency } from "@/hooks/useDependencies";
import { useBlockingChain } from "@/hooks/useBlockingChain";
import { roadmapLevelMeta } from "@/lib/roadmapLevel";
import type {
  EpicResponse,
  StoryResponse,
  TaskResponse,
  ExternalBlockerRef,
  DependencyEdgeResponse,
  BlockableItemType,
} from "@/lib/types";

export type RoadmapDetailItem =
  | { itemType: "epic"; item: EpicResponse }
  | { itemType: "story"; item: StoryResponse }
  | { itemType: "task"; item: TaskResponse };

/** A Story or Task in the current Epic, in the shape the "add blocker" picker needs. */
export interface BlockableItemRef {
  id: string;
  itemType: BlockableItemType;
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
   * Each entry carries `internalItemId` — the specific in-Epic Story/Task its
   * dependency edge touches — so callers pass the whole, unfiltered list and
   * this component narrows it per selected node (see `ExternalBlockersSection`
   * below). An Epic node has no single `internalItemId` to match against, so
   * it sees every external blocker in the Epic as summary context.
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

/**
 * One direction-labeled group of external-blocker badges (see
 * `ExternalBlockersSection`). Unlike the roadmap canvas's external node —
 * which dedups by identity (Decision 4) and can end up representing edges of
 * both directions at once, forcing direction-neutral wording (see the
 * `RoadmapExternalNode` tooltip fix) — each `ExternalBlockerRef` here is a
 * single, un-deduped edge (`RoadmapGraph.tsx` passes `snapshot.externalBlockers`
 * straight through), so its `direction` can be shown accurately per badge.
 */
function ExternalBlockerGroup({
  heading,
  blockers,
}: {
  heading: string;
  blockers: ExternalBlockerRef[];
}) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-medium text-muted-foreground">{heading}</h3>
      <ul className="flex flex-wrap gap-2">
        {blockers.map((blocker) => (
          <li
            key={`${blocker.itemType}-${blocker.itemId}-${blocker.internalItemId}-${blocker.direction}`}
          >
            <Link
              to={`/roadmap/epics/${blocker.epicId}`}
              data-testid="roadmap-external-blocker-badge"
              className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
              title={`${blocker.title} (${blocker.epicTitle})`}
            >
              <ExternalLink className="size-3" />
              {blocker.title}
              <span className="text-muted-foreground">in {blocker.epicTitle}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * External-blockers sidebar section. Splits by `direction` into two
 * sub-groups — "Blocked by" (the external item blocks the selected in-Epic
 * item) and "Blocking" (the selected in-Epic item blocks the external item)
 * — instead of listing every entry under one undifferentiated heading, which
 * would tell the user the opposite of reality for a BLOCKED-direction entry
 * (see the regression tests below for the specific case this fixes).
 */
function ExternalBlockersSection({ blockers }: { blockers: ExternalBlockerRef[] }) {
  if (blockers.length === 0) return null;
  const blockingUs = blockers.filter((b) => b.direction === "BLOCKING");
  const weBlock = blockers.filter((b) => b.direction === "BLOCKED");
  return (
    <div data-testid="roadmap-external-blockers" className="pt-3 border-t space-y-2">
      {blockingUs.length > 0 && (
        <ExternalBlockerGroup heading="Blocked by (other Epics)" blockers={blockingUs} />
      )}
      {weBlock.length > 0 && (
        <ExternalBlockerGroup heading="Blocking (other Epics)" blockers={weBlock} />
      )}
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
  itemType: BlockableItemType;
  itemId: string;
  epicId: string;
  dependencies: DependencyEdgeResponse[];
  blockableItems: BlockableItemRef[];
}) {
  const createDependency = useCreateDependency(epicId);
  const deleteDependency = useDeleteDependency(epicId);
  const [selectedBlockerId, setSelectedBlockerId] = useState("");

  const blockedByEdges = dependencies.filter((d) => d.blockedItemId === itemId);
  const alreadyBlockedByIds = new Set(blockedByEdges.map((d) => d.blockingItemId));
  // Exclude the node itself (can't block itself) and any item that's already a
  // blocker — offering it again would just round-trip to the backend's
  // "dependency edge already exists" 400 with no useful explanation in the UI.
  const pickerOptions = blockableItems.filter(
    (i) => i.id !== itemId && !alreadyBlockedByIds.has(i.id),
  );

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
                  disabled={deleteDependency.isPending}
                  className="ml-1 text-muted-foreground hover:text-foreground disabled:opacity-50 disabled:pointer-events-none"
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
          <Select
            value={selectedBlockerId}
            onValueChange={(v) => setSelectedBlockerId(v ?? "")}
          >
            <SelectTrigger
              data-testid="roadmap-add-blocker-select"
              aria-label="Add blocking dependency"
              size="sm"
              className="w-auto"
            >
              <SelectValue placeholder="Select an item…" />
            </SelectTrigger>
            <SelectContent>
              {pickerOptions.map((opt) => (
                <SelectItem key={opt.id} value={opt.id}>
                  {opt.title} ({opt.itemType})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
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

  // Epics have no `readiness` field at all (BlockableItemType has no "epic"
  // variant), so this is only ever enabled for a BLOCKED Story/Task. Hooks
  // must be called unconditionally on every render, so the item-type/id
  // arguments are always computed (harmlessly unused when itemType is
  // "epic") and only `enabled` varies — mirrors the `itemType !== "epic" &&
  // <ReadinessBadge readiness={item.readiness} />` narrowing pattern used below.
  const chainQuery = useBlockingChain(
    itemType === "task" ? "task" : "story",
    item.id,
    itemType !== "epic" && item.readiness === "BLOCKED",
  );

  return (
    <div data-testid="roadmap-detail-panel" className="p-4 space-y-4">
      <div className="space-y-2">
        <span
          data-testid="roadmap-detail-item-type"
          className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
        >
          {roadmapLevelMeta(itemType).label}
        </span>
        <h2 data-testid="roadmap-detail-title" className="text-lg font-semibold break-words">
          {item.title}
        </h2>
        <div data-testid="roadmap-detail-status" className="flex items-center gap-2">
          {statusBadge(item.status)}
          {itemType !== "epic" && (
            <ReadinessBadge readiness={item.readiness} data-testid="roadmap-detail-readiness-badge" />
          )}
        </div>
      </div>

      <div data-testid="roadmap-detail-description">
        <h3 className="text-sm font-medium text-muted-foreground mb-2">Description</h3>
        <MarkdownViewer content={item.description} maxHeight="max-h-48" />
      </div>

      {itemType === "task" && (
        <div className="pt-3 border-t">
          <h3 className="text-sm font-medium text-muted-foreground mb-2">
            Run history
            {item.totalRunCount > item.recentRuns.length && (
              <span data-testid="roadmap-detail-run-history-total" className="ml-1 font-normal">
                (showing {item.recentRuns.length} of {item.totalRunCount})
              </span>
            )}
          </h3>
          {/* Embedded on the graph response itself (Decision 3) — no follow-up
              request needed just to show recent runs. */}
          <TaskRunHistoryList runs={item.recentRuns} isLoading={false} />
          <Link
            to={`/tasks/${item.id}`}
            className="mt-2 inline-flex items-center gap-1 text-xs text-primary hover:underline"
          >
            <ExternalLink className="size-3" />
            Open Task detail{item.totalRunCount > item.recentRuns.length ? " for full run history" : ""}
          </Link>
        </div>
      )}

      {itemType !== "epic" && (
        <BlockingDependenciesSection
          // Remount on item change so the uncommitted picker selection
          // (selectedBlockerId) can't leak from one node to the next — see
          // the regression test for the bug this prevents.
          key={item.id}
          itemType={itemType}
          itemId={item.id}
          epicId={epicId}
          dependencies={dependencies}
          blockableItems={blockableItems}
        />
      )}

      <ExternalBlockersSection
        blockers={
          itemType === "epic"
            ? externalBlockers
            : externalBlockers.filter((b) => b.internalItemId === item.id)
        }
      />

      {itemType !== "epic" && item.readiness === "BLOCKED" && (
        <BlockingChainSection chain={chainQuery.data} isLoading={chainQuery.isLoading} />
      )}
    </div>
  );
}
