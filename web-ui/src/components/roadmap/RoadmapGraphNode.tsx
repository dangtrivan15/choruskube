import { memo } from "react";
import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import { ChevronDown, ChevronRight, Lock } from "lucide-react";
import { cn } from "@/lib/utils";
import { statusColorTokens } from "@/lib/statusColors";
import { roadmapLevelMeta } from "@/lib/roadmapLevel";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import type { Readiness } from "@/lib/types";

export type RoadmapItemType = "epic" | "story" | "task";

export interface RoadmapGraphNodeData {
  label: string;
  itemType: RoadmapItemType;
  /** Work-item status: "backlog" | "in_progress" | "done". */
  status: string;
  /**
   * Dependency-readiness (Decision 2). `null`/undefined (Epics, which can't
   * participate in a dependency edge, or a not-yet-computed value) renders no
   * indicator — only an explicit "BLOCKED" does.
   */
  readiness?: Readiness | null;
  /**
   * Prioritization level (Epic/Story). Typed loosely and optional: a Task has
   * none, and a stale/older payload may omit it — `PriorityBadge` renders
   * nothing for an unknown value rather than crashing.
   */
  priority?: string | null;
  /** Direct child count (Stories under an Epic, Tasks under a Story). Undefined/0 for a leaf Task. */
  childCount?: number;
  /** Whether this node's children are currently hidden. Ignored when `childCount` is falsy. */
  collapsed?: boolean;
  onToggleCollapse?: (nodeId: string) => void;
  [key: string]: unknown;
}

export type RoadmapGraphNodeType = Node<RoadmapGraphNodeData, "roadmap">;

/**
 * Work-item statuses (backlog/in_progress/done) don't share a vocabulary with
 * run-execution statuses (running/awaiting_human/...), but the same semantic
 * color tokens apply once translated: backlog reads as neutral/pending,
 * in_progress as the "active" info color, done as success.
 */
const STATUS_TOKEN_MAP: Record<string, string> = {
  backlog: "pending",
  in_progress: "running",
  done: "completed",
};

function getStatusColors(status: string) {
  const tokens = statusColorTokens(STATUS_TOKEN_MAP[status] ?? status);
  return {
    bg: `${tokens.bg}/10`,
    border: `${tokens.border}/60`,
    text: tokens.text,
  };
}

/** Primary handle style: visible dot (top-target, bottom-source). */
const primaryHandleClass = "!bg-muted-foreground !size-2";
/** Secondary handle style: invisible, zero-size (used for routing only). */
const secondaryHandleClass = "!bg-transparent !size-0 !min-w-0 !min-h-0 !border-0";

function RoadmapGraphNode({ id, data, selected }: NodeProps<RoadmapGraphNodeType>) {
  const colors = getStatusColors(data.status);
  const hasChildren = (data.childCount ?? 0) > 0;
  const isBlocked = data.readiness === "BLOCKED";
  const ItemTypeIcon = roadmapLevelMeta(data.itemType).Icon;

  return (
    <>
      <Handle id="target-top" type="target" position={Position.Top} className={primaryHandleClass} />
      <Handle id="target-left" type="target" position={Position.Left} className={secondaryHandleClass} />
      <Handle id="target-right" type="target" position={Position.Right} className={secondaryHandleClass} />
      <Handle id="target-bottom" type="target" position={Position.Bottom} className={secondaryHandleClass} />

      <div
        data-testid="roadmap-graph-node"
        data-item-type={data.itemType}
        data-label={data.label}
        data-collapsed={hasChildren ? (data.collapsed ? "true" : "false") : undefined}
        className={cn(
          "relative rounded-lg border-2 px-3 py-2 shadow-sm transition-shadow",
          "w-[160px]",
          colors.bg,
          colors.border,
          selected && "ring-2 ring-ring ring-offset-2 ring-offset-background",
        )}
      >
        <div className="flex items-center gap-2">
          <span className={cn("shrink-0", colors.text)}>
            <ItemTypeIcon className="size-5" />
          </span>
          <span className="truncate text-sm font-medium">{data.label}</span>
          {hasChildren && (
            <button
              type="button"
              data-testid="roadmap-graph-node-toggle-collapse"
              aria-label={data.collapsed ? "Expand" : "Collapse"}
              className="nodrag ml-auto shrink-0 rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
              onClick={(event) => {
                event.stopPropagation();
                data.onToggleCollapse?.(id);
              }}
            >
              {data.collapsed ? (
                <ChevronRight className="size-3.5" />
              ) : (
                <ChevronDown className="size-3.5" />
              )}
            </button>
          )}
        </div>

        <div className="mt-1 flex items-center justify-between gap-2">
          <span className={cn("text-xs font-medium capitalize", colors.text)}>
            {data.status.replace(/_/g, " ")}
          </span>
          <div className="flex shrink-0 items-center gap-1">
            <PriorityBadge priority={data.priority} size="compact" data-testid="roadmap-graph-node-priority" />
            {isBlocked && (
              <span
                data-testid="roadmap-graph-node-blocked-badge"
                title="Blocked by an unfinished dependency"
                className="inline-flex items-center gap-0.5 rounded-full bg-status-warning/15 px-1.5 py-0.5 text-[10px] font-medium text-status-warning"
              >
                <Lock className="size-2.5" />
                Blocked
              </span>
            )}
            {hasChildren && (
              <span
                data-testid="roadmap-graph-node-child-count"
                className="rounded-full bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground"
              >
                {data.childCount}
              </span>
            )}
          </div>
        </div>
      </div>

      <Handle id="source-bottom" type="source" position={Position.Bottom} className={primaryHandleClass} />
      <Handle id="source-left" type="source" position={Position.Left} className={secondaryHandleClass} />
      <Handle id="source-right" type="source" position={Position.Right} className={secondaryHandleClass} />
      <Handle id="source-top" type="source" position={Position.Top} className={secondaryHandleClass} />
    </>
  );
}

export default memo(RoadmapGraphNode);
