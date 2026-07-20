import { memo } from "react";
import { BaseEdge, type EdgeProps, type Edge } from "@xyflow/react";
import { buildOrthogonalPath } from "@/components/runs/DagEdge";
import type { ElkPoint } from "@/lib/elkLayout";

export interface RoadmapGraphEdgeData {
  points?: ElkPoint[];
  [key: string]: unknown;
}

export type RoadmapGraphEdgeType = Edge<RoadmapGraphEdgeData, "roadmap-hierarchy">;

const CORNER_RADIUS = 12;

/**
 * Hierarchy (tree) edge — Epic -> Story, Story -> Task. Plain solid muted
 * line, no arrowhead: the tree shape itself communicates direction, and this
 * needs to read as visually "quieter" than a RoadmapDependencyEdge so the
 * blocking edges stand out.
 */
function RoadmapGraphEdge({ sourceX, sourceY, targetX, targetY, data }: EdgeProps<RoadmapGraphEdgeType>) {
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
      path={edgePath}
      style={{
        stroke: "var(--color-muted-foreground)",
        strokeWidth: 1.25,
        opacity: 0.5,
      }}
    />
  );
}

export default memo(RoadmapGraphEdge);
