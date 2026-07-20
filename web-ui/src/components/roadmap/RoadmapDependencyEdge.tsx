import { memo } from "react";
import { BaseEdge, type EdgeProps, type Edge } from "@xyflow/react";
import { buildOrthogonalPath } from "@/components/runs/DagEdge";
import type { ElkPoint } from "@/lib/elkLayout";

export interface RoadmapDependencyEdgeData {
  points?: ElkPoint[];
  [key: string]: unknown;
}

export type RoadmapDependencyEdgeType = Edge<RoadmapDependencyEdgeData, "roadmap-dependency">;

const CORNER_RADIUS = 12;

/**
 * Blocking-dependency edge — visually distinct from a RoadmapGraphEdge
 * (hierarchy) edge: dashed, warning-colored, with an arrowhead so the
 * blocking -> blocked direction is unambiguous (unlike hierarchy edges, this
 * can point across branches, not just top-to-bottom within one lineage).
 *
 * `markerEnd` is forwarded straight through from `EdgeProps` (same as
 * DagEdge) — React Flow resolves the `Edge.markerEnd` config object set at
 * the Edge-building call site (RoadmapGraph, mirroring RunDag) into an actual
 * `<marker>` def plus this already-resolved reference string.
 */
function RoadmapDependencyEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  data,
  markerEnd,
}: EdgeProps<RoadmapDependencyEdgeType>) {
  const elkPoints = data?.points;
  const points: ElkPoint[] =
    elkPoints && elkPoints.length >= 2
      ? [{ x: sourceX, y: sourceY }, ...elkPoints.slice(1, -1), { x: targetX, y: targetY }]
      : [
          { x: sourceX, y: sourceY },
          { x: targetX, y: targetY },
        ];

  const edgePath = buildOrthogonalPath(points, CORNER_RADIUS);

  return (
    <BaseEdge
      id={id}
      path={edgePath}
      markerEnd={markerEnd}
      style={{
        stroke: "var(--color-status-warning)",
        strokeWidth: 1.75,
        strokeDasharray: "6 4",
      }}
    />
  );
}

export default memo(RoadmapDependencyEdge);
