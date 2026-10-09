import { useEffect, useRef, type RefObject } from "react";
import {
  useReactFlow,
  useStore,
  useStoreApi,
  type ReactFlowState,
  type Rect as FlowRect,
} from "@xyflow/react";
import { computeInitialViewport, type Rect } from "@/lib/dagViewport";

interface DagViewportControllerProps {
  /** Changes whenever the viewport should be recomputed from scratch — the run id combined
   * with the graph's topology key, so switching to another run of the same template (same
   * topology) still resets, instead of inheriting the previous run's viewport. */
  resetKey: string;
  focusNodeId: string | null;
  compact: boolean;
  /** Set by the caller's `onMove`/`Controls` handlers; this controller clears it whenever
   * `resetKey` changes, in the same effect that re-applies, so a stale `true` from the
   * previous run can never suppress the new run's first apply. */
  userMovedRef: RefObject<boolean>;
  /** Called with `resetKey` the first time a viewport is applied for that key. */
  onApplied: (resetKey: string) => void;
}

function toRect(r: FlowRect): Rect {
  return { x: r.x, y: r.y, w: r.width, h: r.height };
}

/**
 * Read from the store's internal nodes rather than `useNodesInitialized()`: that flag is derived
 * from the caller's node objects, which never receive `measured` when `<ReactFlow nodes>` is
 * controlled without an `onNodesChange` (RunDag's setup), so it would stay `false` forever.
 */
function allNodesMeasured(s: ReactFlowState): boolean {
  if (s.nodeLookup.size === 0) return false;
  for (const node of s.nodeLookup.values()) {
    if (!node.measured.width || !node.measured.height) return false;
  }
  return true;
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
  const store = useStoreApi();
  const nodesMeasured = useStore(allNodesMeasured);
  const width = useStore((s) => s.width);
  const height = useStore((s) => s.height);

  const lastResetKeyRef = useRef<string | null>(null);
  const appliedForKeyRef = useRef<string | null>(null);
  const resizeTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Reads the store at call time, not the render-time values: the effect below can run in the
  // commit that hands React Flow new node objects, before they are re-measured.
  function apply() {
    if (userMovedRef.current) return;
    const state = store.getState();
    if (state.width <= 0 || state.height <= 0 || !allNodesMeasured(state)) return;

    const nodeIds = [...state.nodeLookup.keys()];
    const graph = toRect(reactFlow.getNodesBounds(nodeIds));
    const focus =
      focusNodeId && state.nodeLookup.has(focusNodeId)
        ? toRect(reactFlow.getNodesBounds([focusNodeId]))
        : null;
    const viewport = computeInitialViewport({
      width: state.width,
      height: state.height,
      graph,
      focus,
      compact,
    });
    if (!viewport) return;

    reactFlow.setViewport(viewport);
    if (appliedForKeyRef.current !== resetKey) {
      appliedForKeyRef.current = resetKey;
      onApplied(resetKey);
    }
  }

  // Apply as soon as this resetKey's nodes are measured (a fresh run, or a topology change).
  // Not debounced — there's nothing to coalesce on first paint.
  useEffect(() => {
    if (lastResetKeyRef.current !== resetKey) {
      lastResetKeyRef.current = resetKey;
      userMovedRef.current = false;
    }
    if (!nodesMeasured) return;
    apply();
    // `apply` reads everything else from the store or from refs at call time.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [nodesMeasured, resetKey, focusNodeId, compact]);

  // Re-apply on container resize, debounced so a drag-resize of the docked panel doesn't
  // recompute on every intermediate frame.
  useEffect(() => {
    if (!nodesMeasured) return;
    if (resizeTimeoutRef.current) clearTimeout(resizeTimeoutRef.current);
    resizeTimeoutRef.current = setTimeout(apply, RESIZE_DEBOUNCE_MS);
    return () => {
      if (resizeTimeoutRef.current) clearTimeout(resizeTimeoutRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [width, height]);

  return null;
}
