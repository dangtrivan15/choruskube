import { memo } from "react";
import { Handle, Position, type NodeProps, type Node } from "@xyflow/react";
import { Link } from "react-router";
import { ExternalLink } from "lucide-react";

export interface RoadmapExternalNodeData {
  /** The external item's own title (ExternalBlockerRef.title). */
  title: string;
  /** The external item's owning Epic (ExternalBlockerRef.epicId/epicTitle) — the navigation target. */
  epicId: string;
  epicTitle: string;
  [key: string]: unknown;
}

export type RoadmapExternalNodeType = Node<RoadmapExternalNodeData, "roadmap-external">;

/**
 * A lightweight "external" stub node standing in for a Story/Task that lives
 * in a *different* Epic (Decision 1 in the cross-Epic-blockers spec). It is a
 * doorway, not a destination (Decision 5): it shows just enough to identify
 * the blocker (its title, its owning Epic) and links straight to that other
 * Epic's own **graph** route — deliberately `/roadmap/epics/{epicId}/graph`,
 * not `/roadmap/epics/{epicId}` (the Epic *detail* page, which is what the
 * sidebar's own external-blocker links in RoadmapGraphDetailPanel navigate
 * to). The graph route is the right target here because the point of putting
 * this on the canvas at all is to let the user keep seeing and navigating
 * dependencies on the graph itself, not to land them on a list page.
 *
 * Only exposes handles at the top (`target-top`/`source-top`): layout
 * (computeRoadmapTreeLayout) always attaches an external node as a leaf
 * hanging below the in-Epic node it connects to, so both a BLOCKED edge
 * (in-Epic -> external, terminating here) and a BLOCKING edge (external ->
 * in-Epic, originating here) physically meet this node at the same point.
 */
function RoadmapExternalNode({ data }: NodeProps<RoadmapExternalNodeType>) {
  return (
    <>
      <Handle id="target-top" type="target" position={Position.Top} className="!size-2 !bg-status-accent" />
      <Handle
        id="source-top"
        type="source"
        position={Position.Top}
        className="!size-0 !min-h-0 !min-w-0 !border-0 !bg-transparent"
      />

      <Link
        to={`/roadmap/epics/${data.epicId}/graph`}
        data-testid="roadmap-external-node"
        data-label={data.title}
        title={`Blocked by "${data.title}" in ${data.epicTitle} — open that Epic's graph`}
        className="nodrag flex w-[160px] items-center gap-1.5 rounded-md border border-dashed border-status-accent/60 bg-status-accent/10 px-2 py-1.5 text-xs text-status-accent shadow-sm hover:bg-status-accent/20"
        onClick={(event) => event.stopPropagation()}
      >
        <ExternalLink className="size-3 shrink-0" />
        <span className="min-w-0">
          <span className="block truncate font-medium">{data.title}</span>
          <span className="block truncate text-[10px] opacity-80">{data.epicTitle}</span>
        </span>
      </Link>
    </>
  );
}

export default memo(RoadmapExternalNode);
