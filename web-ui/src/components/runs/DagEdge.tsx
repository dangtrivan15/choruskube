import { memo } from "react";
import {
  BaseEdge,
  EdgeLabelRenderer,
  type EdgeProps,
  type Edge,
} from "@xyflow/react";
import { getEdgeColor } from "@/lib/dagLayout";
import type { ElkPoint } from "@/lib/elkLayout";

export interface DagEdgeData {
  condition: string | null;
  /**
   * Colour-only signal. `"failed"` for traversed rejected/failed branches
   * (red), the target's status for the active running edge, otherwise
   * `"pending"` (muted grey). Decoupled from `isActive` so a running edge
   * with a negative condition can render red *and* animate.
   */
  effectiveStatus: string;
  /** True when this edge is the most-recently-routed edge into an active node. */
  isActive: boolean;
  points?: ElkPoint[];
  labelPosition?: { x: number; y: number };
  [key: string]: unknown;
}

export type DagEdgeType = Edge<DagEdgeData, "dag">;

const CORNER_RADIUS = 12;

/**
 * Build an SVG path that traces an orthogonal polyline through `points`,
 * rounding each interior corner with a quadratic curve of `radius` (capped
 * at half the shorter adjacent segment so adjacent corners do not overlap).
 */
export function buildOrthogonalPath(points: ElkPoint[], radius: number): string {
  if (points.length < 2) return "";
  if (points.length === 2) {
    return `M ${points[0].x},${points[0].y} L ${points[1].x},${points[1].y}`;
  }
  let d = `M ${points[0].x},${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const prev = points[i - 1];
    const cur = points[i];
    const next = points[i + 1];
    const inDx = cur.x - prev.x;
    const inDy = cur.y - prev.y;
    const inLen = Math.hypot(inDx, inDy) || 1;
    const outDx = next.x - cur.x;
    const outDy = next.y - cur.y;
    const outLen = Math.hypot(outDx, outDy) || 1;
    const effectiveR = Math.min(radius, inLen / 2, outLen / 2);
    const inR = effectiveR;
    const outR = effectiveR;
    const beforeX = cur.x - (inDx / inLen) * inR;
    const beforeY = cur.y - (inDy / inLen) * inR;
    const afterX = cur.x + (outDx / outLen) * outR;
    const afterY = cur.y + (outDy / outLen) * outR;
    d += ` L ${beforeX},${beforeY} Q ${cur.x},${cur.y} ${afterX},${afterY}`;
  }
  const last = points[points.length - 1];
  d += ` L ${last.x},${last.y}`;
  return d;
}

function DagEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  data,
  markerEnd,
}: EdgeProps<DagEdgeType>) {
  const effectiveStatus = data?.effectiveStatus ?? "pending";
  const color = getEdgeColor(effectiveStatus);
  const isActive = data?.isActive ?? false;
  // Traversed-but-not-active negative branches render red — kept slightly more
  // opaque than the neutral mute so the colour signal isn't washed out.
  const isNegativeMuted = !isActive && effectiveStatus === "failed";

  // Snap ELK's first/last points to ReactFlow's real handle positions so edges
  // terminate exactly on the visible handle dots even when ELK's bbox dimensions
  // (200×64) differ from the actual rendered node size. Interior bend points are
  // preserved — they are in open space where small offsets are invisible.
  const elkPoints = data?.points;
  const points: ElkPoint[] =
    elkPoints && elkPoints.length >= 2
      ? [
          { x: sourceX, y: sourceY },
          ...elkPoints.slice(1, -1), // keep ELK's interior bend points only
          { x: targetX, y: targetY },
        ]
      : [
          { x: sourceX, y: sourceY },
          { x: targetX, y: targetY },
        ];

  const edgePath = buildOrthogonalPath(points, CORNER_RADIUS);

  const labelPos =
    data?.labelPosition ??
    (points.length > 0 ? points[Math.floor(points.length / 2)] : undefined);

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        style={{
          stroke: color,
          // Active (running) edges dominate; everything else recedes into the
          // background via lower opacity + thinner stroke. `opacity` on the
          // path element propagates to the SVG markerEnd so the arrowhead
          // fades along with the line.
          strokeWidth: isActive ? 2 : 1.25,
          strokeDasharray: isActive ? "5 5" : undefined,
          animation: isActive ? "dagEdgeDash 0.5s linear infinite" : undefined,
          opacity: isActive ? 1 : isNegativeMuted ? 0.5 : 0.3,
        }}
      />
      {data?.condition && labelPos && (
        <EdgeLabelRenderer>
          <div
            style={{
              position: "absolute",
              transform: `translate(-50%, -50%) translate(${labelPos.x}px,${labelPos.y}px)`,
              pointerEvents: "all",
            }}
            className="nodrag nopan rounded-md border bg-background px-1.5 py-0.5 text-[9px] font-medium text-muted-foreground shadow-sm"
          >
            {data.condition}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}

export default memo(DagEdge);
