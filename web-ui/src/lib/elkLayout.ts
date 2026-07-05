import ELK from "elkjs/lib/elk.bundled.js";
import type { ElkExtendedEdge, ElkLabel, ElkNode, ElkPort } from "elkjs/lib/elk-api";
import type { GraphSnapshot } from "./types";
import type { NodePosition } from "./dagLayout";

export interface ElkPoint {
  x: number;
  y: number;
}

export interface ElkEdgeRoute {
  /** Source point, bend points (if any), then target point. Always >= 2 entries. */
  points: ElkPoint[];
  /** ELK-placed label rect, if the edge had a label. */
  label?: { x: number; y: number; width: number; height: number };
}

export interface ElkLayoutResult {
  /** Node top-left positions as returned by ELK (overrides NodePosition's "center" JSDoc, which referred to the legacy Dagre output). */
  nodes: Map<string, NodePosition>;
  /** Routes keyed by `${source}->${target}${:condition?}` — matches `RunDag`'s edge id format via `elkEdgeId`. */
  edges: Map<string, ElkEdgeRoute>;
}

export const ELK_NODE_WIDTH = 160;
export const ELK_NODE_HEIGHT = 64;

/**
 * Conservative width estimate for an edge-condition label, in pixels.
 * Sized for the DagEdge label rendering (9 px font + 6 px horizontal padding
 * × 2 + 2 px border × 2). Drives the minimum inter-layer gap, so keep this in
 * lockstep with the actual rendered label width — too small and labels overlap
 * neighbor edges; too large and the DAG stretches needlessly wide.
 */
function estimateLabelWidth(text: string): number {
  return Math.ceil(text.length * 5.4) + 14;
}

const elk = new ELK();

const ELK_OPTIONS: Record<string, string> = {
  "elk.algorithm": "layered",
  "elk.direction": "DOWN",
  "elk.layered.layering.strategy": "INTERACTIVE",
  "elk.layered.cycleBreaking.strategy": "INTERACTIVE",
  "elk.layered.crossingMinimization.semiInteractive": "true",
  "elk.edgeRouting": "ORTHOGONAL",
  "elk.edgeLabels.placement": "H_CENTER V_CENTER",
  // With direction=DOWN, `nodeNode` is the horizontal sibling gap (must fit
  // edge labels like "need_human_decision" sitting on vertical edges between
  // adjacent columns). `nodeNodeBetweenLayers` is the vertical gap; the
  // *visible* gap is also inflated by `edgeNodeBetweenLayers` per routing
  // channel, so keep both tight to avoid bloated layer gaps when back-edges
  // and labeled forward edges share the same gap.
  "elk.spacing.nodeNode": "48",
  "elk.spacing.edgeNode": "4",
  "elk.spacing.edgeEdge": "4",
  "elk.layered.spacing.nodeNodeBetweenLayers": "16",
  "elk.layered.spacing.edgeNodeBetweenLayers": "4",
  "elk.layered.spacing.edgeEdgeBetweenLayers": "4",
};

/**
 * Define ELK ports that mirror DagNode's <Handle> positions.
 * Coordinates are relative to the node's top-left, in node-local space.
 *
 * ELK requires **globally unique** port IDs across the entire graph (unlike
 * React Flow's Handle ids, which are scoped to their node). Port ids are
 * therefore prefixed with the node id: `${nodeId}__${handleId}`.
 *
 * DagNode handle layout (top-to-bottom flow):
 *   - target-top at (w/2, 0), source-bottom at (w/2, h) — primary (visible dots)
 *   - target-left / source-left at (0, h/2), target-right / source-right at (w, h/2)
 *   - source-top / target-bottom exist but are unused for routing in practice
 */
function buildPortsFor(nodeId: string): ElkPort[] {
  const halfW = ELK_NODE_WIDTH / 2;
  const halfH = ELK_NODE_HEIGHT / 2;
  const p = (handle: string, x: number, y: number, side: string): ElkPort => ({
    id: `${nodeId}__${handle}`,
    x,
    y,
    width: 0,
    height: 0,
    layoutOptions: { "elk.port.side": side },
  });
  return [
    // West side
    p("target-left",   0,              halfH,           "WEST"),
    p("source-left",   0,              halfH,           "WEST"),
    // East side
    p("source-right",  ELK_NODE_WIDTH, halfH,           "EAST"),
    p("target-right",  ELK_NODE_WIDTH, halfH,           "EAST"),
    // North side
    p("target-top",    halfW,          0,               "NORTH"),
    p("source-top",    halfW,          0,               "NORTH"),
    // South side
    p("source-bottom", halfW,          ELK_NODE_HEIGHT, "SOUTH"),
    p("target-bottom", halfW,          ELK_NODE_HEIGHT, "SOUTH"),
  ];
}

/**
 * Compute layered orthogonal layout via ELK in INTERACTIVE mode.
 * No positional hints are passed to ELK — layout is fully topology-driven.
 * The INTERACTIVE layering and cycle-breaking strategies order nodes based on
 * graph topology alone; authored position columns were removed when ELK became
 * the canonical layout source of truth (feat/elk-edge-routing).
 */
export async function computeElkLayout(
  snapshot: GraphSnapshot,
): Promise<ElkLayoutResult> {
  const elkGraph: ElkNode = {
    id: "root",
    layoutOptions: ELK_OPTIONS,
    children: snapshot.nodes.map((n): ElkNode => ({
      id: n.template_node_id,
      width: ELK_NODE_WIDTH,
      height: ELK_NODE_HEIGHT,
      layoutOptions: {
        "elk.portConstraints": "FIXED_POS",
      },
      ports: buildPortsFor(n.template_node_id),
    })),
    edges: snapshot.edges.map((e): ElkExtendedEdge => {
      const id = elkEdgeId(e.source_node_id, e.target_node_id, e.condition);
      return {
        id,
        sources: [`${e.source_node_id}__source-bottom`],
        targets: [`${e.target_node_id}__target-top`],
        ...(e.condition
          ? {
              labels: [
                {
                  id: `${id}:label`,
                  text: e.condition,
                  width: estimateLabelWidth(e.condition),
                  height: 18,
                } satisfies ElkLabel,
              ],
            }
          : {}),
      };
    }),
  };

  const laidOut = await elk.layout(elkGraph);

  const nodes = new Map<string, NodePosition>();
  for (const c of laidOut.children ?? []) {
    nodes.set(c.id, { x: c.x ?? 0, y: c.y ?? 0 });
  }

  const edges = new Map<string, ElkEdgeRoute>();
  for (const e of laidOut.edges ?? []) {
    const section = e.sections?.[0];
    if (!section) continue;
    const points: ElkPoint[] = [
      { x: section.startPoint.x, y: section.startPoint.y },
      ...(section.bendPoints ?? []).map((p) => ({ x: p.x, y: p.y })),
      { x: section.endPoint.x, y: section.endPoint.y },
    ];
    const placedLabel = e.labels?.[0];
    edges.set(e.id, {
      points,
      ...(placedLabel && typeof placedLabel.x === "number" && typeof placedLabel.y === "number"
        ? {
            label: {
              x: placedLabel.x,
              y: placedLabel.y,
              width: placedLabel.width ?? 0,
              height: placedLabel.height ?? 0,
            },
          }
        : {}),
    });
  }

  return { nodes, edges };
}

/**
 * Stable identifier for an edge — must match what RunDag uses when keying.
 */
export function elkEdgeId(source: string, target: string, condition: string | null): string {
  return condition ? `${source}->${target}:${condition}` : `${source}->${target}`;
}
