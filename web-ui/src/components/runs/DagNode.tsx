import { memo } from "react";
import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import { Bot, Terminal, User, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { statusColorTokens } from "@/lib/statusColors";

export interface DagNodeData {
  label: string;
  executorType: string;
  status: string;
  iteration: number;
  /**
   * True for the Supervisor — the template's single edgeless routing hub, pinned beside the
   * laid-out graph rather than positioned as a step within it (RunDag never feeds it to ELK).
   * Drives the dashed border and "out of graph" caption below, so it reads as deliberately
   * separate rather than as an orphaned node.
   */
  isRoutingHub?: boolean;
  [key: string]: unknown;
}

export type DagNodeType = Node<DagNodeData, "dag">;

/**
 * Append DagNode-specific opacity values to the base semantic tokens.
 *
 * `bgActive` / `borderActive` are higher-saturation variants used to make a
 * running / awaiting-human / live-chat node visually pop next to its
 * completed neighbours (which sit at the calm /10 + /60 baseline).
 */
function getStatusColors(status: string) {
  const tokens = statusColorTokens(status);
  return {
    bg: `${tokens.bg}/10`,
    bgActive: `${tokens.bg}/25`,
    border: `${tokens.border}/60`,
    borderActive: tokens.border,
    text: tokens.text,
  };
}

const ACTIVE_STATUSES = new Set(["running", "awaiting_human", "live_chat"]);

function ExecutorIcon({ type }: { type: string }) {
  switch (type) {
    case "ai":
      return <Bot className="size-5" />;
    case "human":
      return <User className="size-5" />;
    case "both":
      return <Users className="size-5" />;
    case "script":
      return <Terminal className="size-5" />;
    default:
      return <Bot className="size-5" />;
  }
}

/** Title-case the snake_case slug for display. */
// eslint-disable-next-line react-refresh/only-export-components
export function formatNodeLabel(label: string): string {
  return label
    .split("_")
    .map((word) => (word ? word.charAt(0).toUpperCase() + word.slice(1) : word))
    .join(" ");
}

/** Primary handle style: visible dot (left-target, right-source). */
const primaryHandleClass = "!bg-muted-foreground !size-2";
/** Secondary handle style: invisible, zero-size (used for routing only). */
const secondaryHandleClass = "!bg-transparent !size-0 !min-w-0 !min-h-0 !border-0";

function DagNode({ data, selected }: NodeProps<DagNodeType>) {
  const colors = getStatusColors(data.status);
  const isActive = ACTIVE_STATUSES.has(data.status);
  const isRoutingHub = data.isRoutingHub === true;

  return (
    <>
      {/* Target handles (4 directions) — top is primary for top-to-bottom flow */}
      <Handle id="target-top" type="target" position={Position.Top} className={primaryHandleClass} />
      <Handle id="target-left" type="target" position={Position.Left} className={secondaryHandleClass} />
      <Handle id="target-right" type="target" position={Position.Right} className={secondaryHandleClass} />
      <Handle id="target-bottom" type="target" position={Position.Bottom} className={secondaryHandleClass} />

      <div
        data-testid="dag-node"
        data-label={data.label}
        data-active={isActive ? "true" : "false"}
        data-routing-hub={isRoutingHub ? "true" : "false"}
        className={cn(
          "relative rounded-lg border-2 px-3 py-2 transition-shadow",
          "w-[160px]",
          isActive ? colors.bgActive : colors.bg,
          isActive ? colors.borderActive : colors.border,
          isActive ? "shadow-md" : "shadow-sm",
          isRoutingHub && "border-dashed",
          selected && "ring-2 ring-ring ring-offset-2 ring-offset-background",
        )}
      >
        {/* Pulse animation ring for active nodes */}
        {isActive && (
          <span
            className={cn(
              "absolute inset-0 rounded-lg border-2 animate-pulse",
              colors.borderActive,
            )}
          />
        )}

        {isRoutingHub && (
          <div
            data-testid="dag-node-routing-hub-caption"
            className="mb-1 text-[9px] font-semibold uppercase tracking-wide text-muted-foreground"
          >
            Out of graph
          </div>
        )}

        <div className="flex items-center gap-2">
          <span className={cn("shrink-0", colors.text)}>
            <ExecutorIcon type={data.executorType} />
          </span>
          <span className="truncate text-sm font-medium">{formatNodeLabel(data.label)}</span>
        </div>

        <div className="mt-1 flex items-center justify-between gap-2">
          <span className={cn("text-xs font-medium capitalize", colors.text)}>
            {data.status.replace(/_/g, " ")}
          </span>
          {data.iteration > 1 && (
            <span className="rounded-full bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
              iter {data.iteration}
            </span>
          )}
        </div>
      </div>

      {/* Source handles (4 directions) — bottom is primary for top-to-bottom flow */}
      <Handle id="source-bottom" type="source" position={Position.Bottom} className={primaryHandleClass} />
      <Handle id="source-left" type="source" position={Position.Left} className={secondaryHandleClass} />
      <Handle id="source-right" type="source" position={Position.Right} className={secondaryHandleClass} />
      <Handle id="source-top" type="source" position={Position.Top} className={secondaryHandleClass} />
    </>
  );
}

export default memo(DagNode);
