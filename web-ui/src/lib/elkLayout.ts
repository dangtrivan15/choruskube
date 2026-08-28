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
  return extractLayoutResult(laidOut);
}

/**
 * Stable identifier for an edge — must match what RunDag uses when keying.
 */
export function elkEdgeId(source: string, target: string, condition: string | null): string {
  return condition ? `${source}->${target}:${condition}` : `${source}->${target}`;
}

// ---------------------------------------------------------------------------
// Roadmap Graph View — tree layout (Epic root, Story/Task descendants, plus
// distinct blocking-dependency edges).
//
// Reuses the same ELK options/ports/node size as the workflow-run DAG above
// (computeElkLayout) so RoadmapGraphNode/RoadmapGraphEdge can share the same
// visual grid, but the input shape is generic hierarchy + dependency edges
// rather than a run's template-node snapshot.
// ---------------------------------------------------------------------------

/** One node in the Epic/Story/Task tree. `parentId` is `null` only for the Epic root. */
export interface RoadmapTreeNode {
  id: string;
  parentId: string | null;
}

/** One "blocking" dependency edge, in the shape the layout needs (id + endpoints). */
export interface RoadmapDependencyEdgeInput {
  id: string;
  source: string;
  target: string;
}

/**
 * One external ("ghost") node attached to the tree — a stub standing in for a
 * Story/Task that lives in a *different* Epic. Deduplicated by external item identity upstream
 * (RoadmapGraph), so there is exactly one of these per unique external item
 * regardless of how many in-Epic nodes it connects to.
 */
export interface RoadmapExternalNodeInput {
  /** roadmapExternalNodeId(itemId) */
  id: string;
}

/**
 * One cross-Epic edge attaching an external node to the in-Epic node it
 * touches. `internalItemId` is always the in-Epic endpoint regardless of
 * `ExternalBlockerRef.direction` — for LAYOUT purposes an external node is
 * always a leaf hanging below the in-Epic node it connects to (mirrors a
 * hierarchy edge's routing: `internalItemId__source-bottom` ->
 * `externalNodeId__target-top`), so it gets a real ELK-computed position
 * instead of floating unpositioned. Which side is semantically the
 * "blocker" (for arrow direction) is a rendering concern the caller
 * (RoadmapGraph) resolves from `direction` when building the React Flow edge.
 */
export interface RoadmapExternalEdgeInput {
  /** roadmapCrossEpicEdgeId(blockerId) */
  id: string;
  externalNodeId: string;
  internalItemId: string;
}

export interface RoadmapTreeInput {
  nodes: RoadmapTreeNode[];
  dependencyEdges: RoadmapDependencyEdgeInput[];
  /** Cross-Epic external nodes/edges. Omit or pass `[]` when there are none. */
  externalNodes?: RoadmapExternalNodeInput[];
  externalEdges?: RoadmapExternalEdgeInput[];
}

/** Stable id for a hierarchy (tree) edge — parent -> child. */
export function roadmapHierarchyEdgeId(parentId: string, childId: string): string {
  return `${parentId}=>${childId}`;
}

/**
 * Stable id for a dependency (blocking) edge. Prefixed and keyed by the
 * dependency row's own id (not its endpoints) since — unlike the tree, where
 * a child has exactly one parent — two Tasks could in principle be linked by
 * more than one dependency row over time, and the row id is what
 * useDeleteDependency needs to target anyway.
 */
export function roadmapDependencyEdgeId(dependencyId: string): string {
  return `dep:${dependencyId}`;
}

/**
 * Stable id for an external ("ghost") node standing in for a Story/Task in
 * another Epic. Keyed by the external item's own id — one node
 * per unique external item (deduplicated), not one per blocker entry.
 */
export function roadmapExternalNodeId(itemId: string): string {
  return `external:${itemId}`;
}

/**
 * Stable id for a cross-Epic dependency edge. `blockerId` is a caller-built
 * composite key identifying one `ExternalBlockerRef` entry — unlike a
 * within-Epic dependency row, `ExternalBlockerRef` has no id of its own, so
 * the caller (RoadmapGraph) combines the external item id with the specific
 * in-Epic item id (`internalItemId`) it connects to, since the same external
 * item can touch more than one in-Epic node.
 */
export function roadmapCrossEpicEdgeId(blockerId: string): string {
  return `cross-epic:${blockerId}`;
}

/** Extract {@link ElkLayoutResult} from a resolved ELK layout — shared by both entry points. */
function extractLayoutResult(laidOut: ElkNode): ElkLayoutResult {
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
 * Compute a layered layout for an Epic's Story/Task tree plus its blocking
 * dependency edges. `input.nodes` should already be filtered down to the
 * currently-visible set (i.e. the caller has removed the descendants of any
 * collapsed node) — this function lays out exactly what it's given.
 *
 * Hierarchy edges route top-to-bottom the same way computeElkLayout's fired
 * run edges do (source-bottom -> target-top); dependency edges reuse the same
 * ports so ELK can route both kinds of edges (including cross-branch
 * dependency edges) without a bespoke port scheme.
 *
 * External nodes (`input.externalNodes`) are added as ordinary ELK children —
 * same size/ports as any tree node — and each `input.externalEdges` entry is
 * routed exactly like a hierarchy edge (`internalItemId__source-bottom` ->
 * `externalNodeId__target-top`), so ELK places every external node as a leaf
 * hanging below the in-Epic node it attaches to, rather than treating it as
 * an unbounded free-floating graph participant that could pull layout weight
 * toward an unrelated part of the tree.
 */
export async function computeRoadmapTreeLayout(input: RoadmapTreeInput): Promise<ElkLayoutResult> {
  const nodeIds = new Set(input.nodes.map((n) => n.id));
  const externalNodes = input.externalNodes ?? [];
  const externalNodeIds = new Set(externalNodes.map((n) => n.id));
  const externalEdges = input.externalEdges ?? [];

  const elkGraph: ElkNode = {
    id: "root",
    layoutOptions: ELK_OPTIONS,
    children: [
      ...input.nodes.map((n): ElkNode => ({
        id: n.id,
        width: ELK_NODE_WIDTH,
        height: ELK_NODE_HEIGHT,
        layoutOptions: {
          "elk.portConstraints": "FIXED_POS",
        },
        ports: buildPortsFor(n.id),
      })),
      ...externalNodes.map((n): ElkNode => ({
        id: n.id,
        width: ELK_NODE_WIDTH,
        height: ELK_NODE_HEIGHT,
        layoutOptions: {
          "elk.portConstraints": "FIXED_POS",
        },
        ports: buildPortsFor(n.id),
      })),
    ],
    edges: [
      ...input.nodes
        .filter((n): n is RoadmapTreeNode & { parentId: string } => n.parentId !== null)
        .map((n): ElkExtendedEdge => ({
          id: roadmapHierarchyEdgeId(n.parentId, n.id),
          sources: [`${n.parentId}__source-bottom`],
          targets: [`${n.id}__target-top`],
        })),
      // Dependency edges whose endpoints are both currently visible. An edge
      // touching a collapsed-away node is simply omitted from this layout —
      // RoadmapGraph re-derives the visible dependency set alongside the
      // visible node set before calling this function.
      ...input.dependencyEdges
        .filter((e) => nodeIds.has(e.source) && nodeIds.has(e.target))
        .map((e): ElkExtendedEdge => ({
          id: roadmapDependencyEdgeId(e.id),
          sources: [`${e.source}__source-bottom`],
          targets: [`${e.target}__target-top`],
        })),
      // Cross-Epic external-node attachments — leaf edges, not dependency
      // edges (see doc comment above): only emitted when both the in-Epic
      // endpoint is currently visible and the external node itself is known.
      ...externalEdges
        .filter((e) => nodeIds.has(e.internalItemId) && externalNodeIds.has(e.externalNodeId))
        .map((e): ElkExtendedEdge => ({
          id: e.id,
          sources: [`${e.internalItemId}__source-bottom`],
          targets: [`${e.externalNodeId}__target-top`],
        })),
    ],
  };

  const laidOut = await elk.layout(elkGraph);
  return extractLayoutResult(laidOut);
}

/**
 * Stable hash of the roadmap tree's topology PLUS the current collapsed-node
 * set. Mirrors RunDag's `buildTopologyKey` (used as a `useEffect` dependency
 * to re-run ELK only when the graph actually changed shape), but lives here
 * rather than in the consuming component because — unlike a workflow run,
 * whose DAG shape never changes after a status update — collapsing/expanding
 * a Story branch changes which nodes/edges are *visible* without changing the
 * underlying data, and that's a layout-affecting change this hash must catch.
 *
 * Deliberately hashes the full (uncollapsed) node/edge set plus the collapsed
 * id set, not the post-filter visible subgraph: two different collapse
 * states can still coincidentally hide the same visible subgraph shape (e.g.
 * collapsing two different empty Stories), and re-deriving "visible topology"
 * here would risk drifting from whatever filtering RoadmapGraph actually
 * applies before calling computeRoadmapTreeLayout.
 */
export function buildRoadmapTopologyKey(
  input: RoadmapTreeInput,
  collapsedNodeIds: ReadonlySet<string>,
): string {
  const nodes = input.nodes
    .map((n) => `${n.id}:${n.parentId ?? ""}`)
    .sort()
    .join("|");
  const deps = input.dependencyEdges
    .map((e) => `${e.id}:${e.source}->${e.target}`)
    .sort()
    .join("|");
  const collapsed = [...collapsedNodeIds].sort().join(",");
  const externalNodes = (input.externalNodes ?? [])
    .map((n) => n.id)
    .sort()
    .join(",");
  const externalEdges = (input.externalEdges ?? [])
    .map((e) => `${e.id}:${e.internalItemId}->${e.externalNodeId}`)
    .sort()
    .join("|");
  return `${nodes}#${deps}#collapsed:${collapsed}#extNodes:${externalNodes}#extEdges:${externalEdges}`;
}
