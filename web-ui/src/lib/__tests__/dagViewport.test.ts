import { describe, it, expect } from "vitest";
import {
  computeInitialViewport,
  PAD,
  MIN_ZOOM,
  FIT_MAX_ZOOM,
  COMPACT_MIN_READABLE_ZOOM,
  COMPACT_ZOOM,
  type Rect,
} from "../dagViewport";

describe("computeInitialViewport", () => {
  it("returns null for a zero-size container", () => {
    expect(
      computeInitialViewport({
        width: 0,
        height: 600,
        graph: { x: 0, y: 0, w: 200, h: 200 },
        focus: null,
        compact: false,
      }),
    ).toBeNull();
    expect(
      computeInitialViewport({
        width: 800,
        height: 0,
        graph: { x: 0, y: 0, w: 200, h: 200 },
        focus: null,
        compact: false,
      }),
    ).toBeNull();
  });

  it("returns null for an empty graph", () => {
    expect(
      computeInitialViewport({
        width: 800,
        height: 600,
        graph: { x: 0, y: 0, w: 0, h: 0 },
        focus: null,
        compact: false,
      }),
    ).toBeNull();
  });

  it("a desktop large graph fits and centres", () => {
    const graph: Rect = { x: 0, y: 0, w: 2000, h: 1000 };
    const result = computeInitialViewport({
      width: 1000,
      height: 600,
      graph,
      focus: null,
      compact: false,
    });
    expect(result).not.toBeNull();
    const expectedZoom = Math.min((1000 - 2 * PAD) / 2000, (600 - 2 * PAD) / 1000);
    expect(result!.zoom).toBeCloseTo(expectedZoom, 5);
    // The graph's centre point, once scaled by zoom and translated by (x, y), lands on
    // the pane's centre.
    const graphCenterX = graph.x + graph.w / 2;
    const graphCenterY = graph.y + graph.h / 2;
    expect(result!.x + graphCenterX * result!.zoom).toBeCloseTo(500, 1);
    expect(result!.y + graphCenterY * result!.zoom).toBeCloseTo(300, 1);
  });

  it("a desktop tiny graph is capped at zoom 1 (never upscaled)", () => {
    const result = computeInitialViewport({
      width: 1200,
      height: 800,
      graph: { x: 0, y: 0, w: 160, h: 64 },
      focus: null,
      compact: false,
    });
    expect(result!.zoom).toBe(FIT_MAX_ZOOM);
  });

  it("clamps an unreasonably large graph down to MIN_ZOOM, not below", () => {
    const result = computeInitialViewport({
      width: 400,
      height: 300,
      graph: { x: 0, y: 0, w: 100_000, h: 100_000 },
      focus: null,
      compact: false,
    });
    expect(result!.zoom).toBe(MIN_ZOOM);
  });

  it("compact with fitZoom at or above the readable threshold fits the whole graph", () => {
    // A graph small enough that fitting it at >= COMPACT_MIN_READABLE_ZOOM still holds.
    const graph: Rect = { x: 0, y: 0, w: 160, h: 64 };
    const result = computeInitialViewport({
      width: 390,
      height: 600,
      graph,
      focus: { x: 0, y: 0, w: 160, h: 64 },
      compact: true,
    });
    const expectedZoom = Math.min((390 - 2 * PAD) / 160, (600 - 2 * PAD) / 64);
    expect(result!.zoom).toBeCloseTo(clampToFitMax(expectedZoom), 5);
  });

  it("compact with a wide graph uses the readable zoom, centred on focus at about one third of the height", () => {
    const graph: Rect = { x: 0, y: 0, w: 2000, h: 1000 };
    const focus: Rect = { x: 900, y: 400, w: 160, h: 64 };
    const result = computeInitialViewport({
      width: 390,
      height: 844,
      graph,
      focus,
      compact: true,
    });
    expect(result).not.toBeNull();
    expect(result!.zoom).toBe(COMPACT_ZOOM);

    const focusCenterX = focus.x + focus.w / 2;
    expect(result!.x + focusCenterX * result!.zoom).toBeCloseTo(390 / 2, 1);

    const focusTopInViewport = result!.y + focus.y * result!.zoom;
    expect(focusTopInViewport).toBeCloseTo(844 / 3 + PAD, 1);
  });

  it("compact with no focus aligns the top of the graph near the top of the viewport", () => {
    const graph: Rect = { x: 0, y: 0, w: 2000, h: 1000 };
    const result = computeInitialViewport({
      width: 390,
      height: 844,
      graph,
      focus: null,
      compact: true,
    });
    expect(result).not.toBeNull();
    expect(result!.zoom).toBe(COMPACT_ZOOM);

    // The top of the (whole) graph sits PAD below the viewport's top edge, not at one third —
    // for a single-entrypoint top-down layout, the graph's top edge is the entry node's top.
    const graphTopInViewport = result!.y + graph.y * result!.zoom;
    expect(graphTopInViewport).toBeCloseTo(PAD, 1);
  });
});

function clampToFitMax(zoom: number): number {
  return Math.min(FIT_MAX_ZOOM, Math.max(MIN_ZOOM, zoom));
}
