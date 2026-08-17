import { memo } from "react";
import { BaseEdge, type EdgeProps, type Edge } from "@xyflow/react";
import { buildOrthogonalPath } from "@/components/runs/DagEdge";
import type { ElkPoint } from "@/lib/elkLayout";

export interface RoadmapEpicDependencyEdgeData {
  points?: ElkPoint[];
  [key: string]: unknown;
}

export type RoadmapEpicDependencyEdgeType = Edge<RoadmapEpicDependencyEdgeData, "roadmap-epic-dependency">;

const CORNER_RADIUS = 12;

/**
 * Epic-tier blocking-dependency edge — a `RoadmapDependencyEdge` whose
 * blocking or blocked endpoint is the Epic itself, rather than one of its
 * Stories/Tasks (the Epic is a candidate in its own right —
 * `EpicReadinessAssembler.loadEpicCandidates` adds the Epic's own id to the
 * candidate set an edge can target). Reuses `RoadmapDependencyEdge`'s
 * geometry/marker wiring but gets its own dash-dot pattern and
 * `--status-info` color so a viewer can tell "this dependency involves the
 * whole Epic, not just one of its Stories/Tasks" at a glance, the same way
 * `RoadmapCrossEpicEdge` is visually distinguished from a within-Epic
 * dependency edge.
 */
function RoadmapEpicDependencyEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  data,
  markerEnd,
}: EdgeProps<RoadmapEpicDependencyEdgeType>) {
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
        stroke: "var(--color-status-info)",
        strokeWidth: 1.75,
        strokeDasharray: "8 3 2 3",
      }}
    />
  );
}

export default memo(RoadmapEpicDependencyEdge);
