import { useEffect, useRef, type RefObject } from "react";
import { useReactFlow, useNodesInitialized, useStore, type Rect as FlowRect } from "@xyflow/react";
import { computeInitialViewport, type Rect } from "@/lib/dagViewport";

interface DagViewportControllerProps {
  /** Changes whenever the viewport should be recomputed from scratch — the run id combined
   * with the graph's topology key, so switching to another run of the same template (same
   * topology) still resets, instead of inheriting the previous run's viewport. */
  resetKey: string;
  focusNodeId: string | null;
  compact: boolean;
  /** Owned by the caller (RunDag) so its `onMove`/`Controls` handlers and this controller
   * agree on one "has the user moved the view" flag, and so RunDag can reset it on `resetKey` change. */
  userMovedRef: RefObject<boolean>;
  /** Called once per `resetKey`, the first time the viewport is actually applied. */
  onApplied: () => void;
}

function toRect(r: FlowRect): Rect {
  return { x: r.x, y: r.y, w: r.width, h: r.height };
}

const RESIZE_DEBOUNCE_MS = 100;

/**
 * Mounted as a child of `<ReactFlow>`. Computes and applies the initial viewport once the
 * laid-out nodes are measured, and re-applies it on container resize — until the user pans,
 * zooms, or presses a zoom/fit control, tracked via `userMovedRef` rather than locally so the
 * canvas's own gesture/button handlers can set it too.
 */
export default function DagViewportController({
  resetKey,
  focusNodeId,
  compact,
  userMovedRef,
  onApplied,
}: DagViewportControllerProps) {
  const reactFlow = useReactFlow();
  const nodesInitialized = useNodesInitialized();
  const width = useStore((s) => s.width);
  const height = useStore((s) => s.height);

  const appliedOnceForKeyRef = useRef<string | null>(null);
  const resizeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  function apply() {
    if (userMovedRef.current) return;
    if (width <= 0 || height <= 0) return;

    const nodeIds = reactFlow.getNodes().map((n) => n.id);
    if (nodeIds.length === 0) return;

    const graph = toRect(reactFlow.getNodesBounds(nodeIds));
    const focus = focusNodeId ? toRect(reactFlow.getNodesBounds([focusNodeId])) : null;
    const viewport = computeInitialViewport({ width, height, graph, focus, compact });
    if (!viewport) return;

    reactFlow.setViewport(viewport);
    if (appliedOnceForKeyRef.current !== resetKey) {
      appliedOnceForKeyRef.current = resetKey;
      onApplied();
    }
  }

  // Apply as soon as this resetKey's nodes are measured (a fresh run, or a topology change).
  // Not debounced — there's nothing to coalesce on first paint.
  useEffect(() => {
    if (!nodesInitialized) return;
    apply();
    // `apply` closes over props/state that already appear in this dependency list (directly
    // or via the refs it reads); re-declaring it as a dependency would re-run this effect on
    // every render instead of only when one of these actually changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodesInitialized, resetKey, focusNodeId, compact]);

  // Re-apply on container resize, debounced so a drag-resize of the docked panel doesn't
  // recompute on every intermediate frame.
  useEffect(() => {
    if (!nodesInitialized) return;
    if (resizeTimeoutRef.current) clearTimeout(resizeTimeoutRef.current);
    resizeTimeoutRef.current = setTimeout(apply, RESIZE_DEBOUNCE_MS);
    return () => {
      if (resizeTimeoutRef.current) clearTimeout(resizeTimeoutRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [width, height]);

  return null;
}
