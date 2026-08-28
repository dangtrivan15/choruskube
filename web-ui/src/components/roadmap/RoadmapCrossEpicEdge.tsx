import { memo } from "react";
import { BaseEdge, type EdgeProps, type Edge } from "@xyflow/react";
import { buildOrthogonalPath } from "@/components/runs/DagEdge";
import type { ElkPoint } from "@/lib/elkLayout";

export interface RoadmapCrossEpicEdgeData {
  points?: ElkPoint[];
  [key: string]: unknown;
}

export type RoadmapCrossEpicEdgeType = Edge<RoadmapCrossEpicEdgeData, "roadmap-cross-epic-dependency">;

const CORNER_RADIUS = 12;

/**
 * Cross-Epic blocking-dependency edge — connects an in-Epic
 * node to a `RoadmapExternalNode` stub standing in for a Story/Task in
 * another Epic. Modeled directly on `RoadmapDependencyEdge` (the within-Epic
 * blocking edge) but deliberately styled distinctly: a
 * different, tighter dash pattern (dotted rather than dashed) plus
 * `--status-accent` instead of `--status-warning` for stroke and marker
 * color, so the two "this is a blocking edge" languages never get confused
 * on a canvas that now has three edge kinds instead of two (see
 * RoadmapGraphLegend). `--status-accent` isn't otherwise claimed by any
 * status meaning inside the roadmap graph view.
 */
function RoadmapCrossEpicEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  data,
  markerEnd,
}: EdgeProps<RoadmapCrossEpicEdgeType>) {
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
        stroke: "var(--color-status-accent)",
        strokeWidth: 1.75,
        strokeDasharray: "2 3",
      }}
    />
  );
}

export default memo(RoadmapCrossEpicEdge);
