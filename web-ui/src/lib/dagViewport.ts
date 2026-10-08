/** An axis-aligned bounding box in graph (flow) coordinates. */
export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

export interface ComputeInitialViewportInput {
  /** Canvas pane size, in CSS px. */
  width: number;
  height: number;
  /** Bounding box of every laid-out node. */
  graph: Rect;
  /**
   * Bounding box of the node to centre on when a readable zoom can't fit the
   * whole graph (a phone). `null` when nothing needs attention — the fallback
   * is then the top of `graph` itself, which for a single-entrypoint top-down
   * layout is exactly the entry node's position.
   */
  focus: Rect | null;
  /** Phone tier — readability is capped from below; desktop/tablet always fit the whole graph. */
  compact: boolean;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export const PAD = 24;
export const MIN_ZOOM = 0.25;
export const FIT_MAX_ZOOM = 1;
export const COMPACT_MIN_READABLE_ZOOM = 0.7;
export const COMPACT_ZOOM = 0.9;

/**
 * Pure computation of the graph's initial React Flow viewport. Wide screens always fit the
 * whole graph (capped at 100% zoom, never upscaled past it). On a phone, if fitting everything
 * would drop below a readable zoom, a fixed readable zoom is used instead, centred on the node
 * that needs attention (or the top of the graph when nothing does) — the node the user cares
 * about is then readable on first paint, instead of the whole graph shrunk to illegibility.
 */
export function computeInitialViewport(input: ComputeInitialViewportInput): Viewport | null {
  const { width, height, graph, focus, compact } = input;
  if (width <= 0 || height <= 0) return null;
  if (graph.w <= 0 || graph.h <= 0) return null;

  const fitZoom = clamp(
    Math.min((width - 2 * PAD) / graph.w, (height - 2 * PAD) / graph.h),
    MIN_ZOOM,
    FIT_MAX_ZOOM,
  );

  if (!compact || fitZoom >= COMPACT_MIN_READABLE_ZOOM) {
    const graphCenterX = graph.x + graph.w / 2;
    const graphCenterY = graph.y + graph.h / 2;
    return {
      x: width / 2 - graphCenterX * fitZoom,
      y: height / 2 - graphCenterY * fitZoom,
      zoom: fitZoom,
    };
  }

  const zoom = COMPACT_ZOOM;
  if (focus) {
    const focusCenterX = focus.x + focus.w / 2;
    return {
      x: width / 2 - focusCenterX * zoom,
      y: height / 3 + PAD - focus.y * zoom,
      zoom,
    };
  }

  const graphCenterX = graph.x + graph.w / 2;
  return {
    x: width / 2 - graphCenterX * zoom,
    y: PAD - graph.y * zoom,
    zoom,
  };
}
