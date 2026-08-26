import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ReactFlow,
  Controls,
  Background,
  MarkerType,
  type Node,
  type Edge,
  type NodeMouseHandler,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { ExternalBlockerRef, Readiness, RoadmapGraphSnapshot } from "@/lib/types";
import { resolveStatusColors } from "@/lib/dagLayout";
import {
  computeRoadmapTreeLayout,
  buildRoadmapTopologyKey,
  roadmapHierarchyEdgeId,
  roadmapDependencyEdgeId,
  roadmapExternalNodeId,
  roadmapCrossEpicEdgeId,
  ELK_NODE_HEIGHT,
  type ElkLayoutResult,
  type RoadmapTreeInput,
  type RoadmapTreeNode,
  type RoadmapExternalNodeInput,
  type RoadmapExternalEdgeInput,
} from "@/lib/elkLayout";
import RoadmapGraphNode, { type RoadmapGraphNodeData, type RoadmapItemType } from "./RoadmapGraphNode";
import RoadmapGraphEdge, { type RoadmapGraphEdgeData } from "./RoadmapGraphEdge";
import RoadmapDependencyEdge, { type RoadmapDependencyEdgeData } from "./RoadmapDependencyEdge";
import RoadmapEpicDependencyEdge, {
  type RoadmapEpicDependencyEdgeData,
} from "./RoadmapEpicDependencyEdge";
import RoadmapExternalNode, { type RoadmapExternalNodeData } from "./RoadmapExternalNode";
import RoadmapCrossEpicEdge, { type RoadmapCrossEpicEdgeData } from "./RoadmapCrossEpicEdge";
import RoadmapGraphLegend from "./RoadmapGraphLegend";
import type { RoadmapDetailItem } from "./RoadmapGraphDetailPanel";

const nodeTypes = { roadmap: RoadmapGraphNode, "roadmap-external": RoadmapExternalNode };
const edgeTypes = {
  "roadmap-hierarchy": RoadmapGraphEdge,
  "roadmap-dependency": RoadmapDependencyEdge,
  "roadmap-epic-dependency": RoadmapEpicDependencyEdge,
  "roadmap-cross-epic-dependency": RoadmapCrossEpicEdge,
};

/** Every node/edge type this canvas can render — internal tree nodes plus external stub nodes. */
type RoadmapFlowNode =
  | Node<RoadmapGraphNodeData, "roadmap">
  | Node<RoadmapExternalNodeData, "roadmap-external">;
type RoadmapFlowEdge =
  | Edge<RoadmapGraphEdgeData, "roadmap-hierarchy">
  | Edge<RoadmapDependencyEdgeData, "roadmap-dependency">
  | Edge<RoadmapEpicDependencyEdgeData, "roadmap-epic-dependency">
  | Edge<RoadmapCrossEpicEdgeData, "roadmap-cross-epic-dependency">;

/** One unique external item (Decision 4's dedup key: `itemType:itemId`). */
interface ExternalNodeInfo {
  id: string;
  title: string;
  epicId: string;
  epicTitle: string;
}

/** Composite key identifying one `ExternalBlockerRef` entry — see roadmapCrossEpicEdgeId's doc comment. */
function blockerKey(blocker: ExternalBlockerRef): string {
  return `${blocker.itemId}:${blocker.internalItemId}`;
}

/** One external node per unique `(itemType, itemId)` — Decision 4. */
function dedupeExternalNodes(blockers: ExternalBlockerRef[]): ExternalNodeInfo[] {
  const byKey = new Map<string, ExternalNodeInfo>();
  for (const blocker of blockers) {
    const key = `${blocker.itemType}:${blocker.itemId}`;
    if (!byKey.has(key)) {
      byKey.set(key, {
        id: roadmapExternalNodeId(blocker.itemId),
        title: blocker.title,
        epicId: blocker.epicId,
        epicTitle: blocker.epicTitle,
      });
    }
  }
  return [...byKey.values()];
}

/**
 * A Story branch with more Tasks than this collapses by default on first
 * render — keeps a large Epic's initial graph readable instead of dumping
 * every Task on screen at once. Purely a display default: expanding is one
 * click away, and this only ever applies once, at mount.
 */
const AUTO_COLLAPSE_TASK_THRESHOLD = 8;

interface InternalNode {
  id: string;
  itemType: RoadmapItemType;
  parentId: string | null;
  label: string;
  status: string;
  /** Epics can't participate in a dependency edge, so this is always null for them. */
  readiness: Readiness | null;
  /**
   * Prioritization level (Epic/Story/Task — Decision 4 of the roadmap
   * dependencies/priorities/milestones feature gave Task its own `priority` too).
   */
  priority: string | null;
}

function buildInternalNodes(snapshot: RoadmapGraphSnapshot): InternalNode[] {
  const nodes: InternalNode[] = [
    {
      id: snapshot.epic.id,
      itemType: "epic",
      parentId: null,
      label: snapshot.epic.title,
      status: snapshot.epic.stage,
      readiness: null,
      priority: snapshot.epic.priority,
    },
  ];
  for (const story of snapshot.stories) {
    nodes.push({
      id: story.id,
      itemType: "story",
      parentId: story.epicId,
      label: story.title,
      status: story.stage,
      readiness: story.readiness,
      priority: story.priority,
    });
  }
  for (const task of snapshot.tasks) {
    nodes.push({
      id: task.id,
      itemType: "task",
      parentId: task.storyId,
      label: task.title,
      status: task.status,
      readiness: task.readiness,
      priority: task.priority,
    });
  }
  return nodes;
}

function computeChildCounts(nodes: InternalNode[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const node of nodes) {
    if (node.parentId === null) continue;
    counts.set(node.parentId, (counts.get(node.parentId) ?? 0) + 1);
  }
  return counts;
}

/** Default collapsed set: every Story whose Task count exceeds the threshold. */
function computeInitialCollapsed(snapshot: RoadmapGraphSnapshot): Set<string> {
  const taskCountByStory = new Map<string, number>();
  for (const task of snapshot.tasks) {
    taskCountByStory.set(task.storyId, (taskCountByStory.get(task.storyId) ?? 0) + 1);
  }
  const collapsed = new Set<string>();
  for (const story of snapshot.stories) {
    if ((taskCountByStory.get(story.id) ?? 0) > AUTO_COLLAPSE_TASK_THRESHOLD) {
      collapsed.add(story.id);
    }
  }
  return collapsed;
}

/** A node is visible unless one of its ancestors is collapsed. */
function isVisible(node: InternalNode, byId: Map<string, InternalNode>, collapsed: ReadonlySet<string>): boolean {
  let parentId = node.parentId;
  while (parentId !== null) {
    if (collapsed.has(parentId)) return false;
    parentId = byId.get(parentId)?.parentId ?? null;
  }
  return true;
}

/**
 * Resolves a graph node id to its detail-panel item from a given snapshot.
 * Exported so callers (RoadmapGraphPage) can re-resolve the *currently
 * selected* node against a freshly-refetched snapshot on every render,
 * instead of holding on to the (possibly stale) object captured at click
 * time — see RoadmapGraphPage's `selectedId` state for why that matters.
 */
export function findDetailItem(nodeId: string, snapshot: RoadmapGraphSnapshot): RoadmapDetailItem | null {
  if (nodeId === snapshot.epic.id) return { itemType: "epic", item: snapshot.epic };
  const story = snapshot.stories.find((s) => s.id === nodeId);
  if (story) return { itemType: "story", item: story };
  const task = snapshot.tasks.find((t) => t.id === nodeId);
  if (task) return { itemType: "task", item: task };
  return null;
}

/** Fallback layout when ELK fails: nodes stacked vertically by index, straight edges. */
function buildFallbackLayout(nodeIds: string[]): ElkLayoutResult {
  const nodes = new Map(nodeIds.map((id, idx) => [id, { x: 0, y: idx * (ELK_NODE_HEIGHT + 30) }] as const));
  return { nodes, edges: new Map() };
}

interface RoadmapGraphProps {
  snapshot: RoadmapGraphSnapshot;
  onNodeSelect: (detail: RoadmapDetailItem | null) => void;
}

export default function RoadmapGraph({ snapshot, onNodeSelect }: RoadmapGraphProps) {
  const [collapsed, setCollapsed] = useState<Set<string>>(() => computeInitialCollapsed(snapshot));

  const allNodes = useMemo(() => buildInternalNodes(snapshot), [snapshot]);
  const nodesById = useMemo(() => new Map(allNodes.map((n) => [n.id, n])), [allNodes]);
  const childCounts = useMemo(() => computeChildCounts(allNodes), [allNodes]);

  const visibleNodes = useMemo(
    () => allNodes.filter((n) => isVisible(n, nodesById, collapsed)),
    [allNodes, nodesById, collapsed],
  );
  const visibleIds = useMemo(() => new Set(visibleNodes.map((n) => n.id)), [visibleNodes]);
  const visibleDependencies = useMemo(
    () =>
      snapshot.dependencies.filter(
        (d) => visibleIds.has(d.blockingItemId) && visibleIds.has(d.blockedItemId),
      ),
    [snapshot.dependencies, visibleIds],
  );
  // A cross-Epic blocker is only shown once the in-Epic node it attaches to
  // is visible — mirrors visibleDependencies above. The external node itself
  // has no collapse state of its own; hiding its one in-Epic anchor hides it.
  const visibleExternalBlockers = useMemo(
    () => snapshot.externalBlockers.filter((b) => visibleIds.has(b.internalItemId)),
    [snapshot.externalBlockers, visibleIds],
  );

  const allExternalNodeInfos = useMemo(
    () => dedupeExternalNodes(snapshot.externalBlockers),
    [snapshot.externalBlockers],
  );
  const visibleExternalNodeInfos = useMemo(
    () => dedupeExternalNodes(visibleExternalBlockers),
    [visibleExternalBlockers],
  );

  const treeInputAll: RoadmapTreeInput = useMemo(
    () => ({
      nodes: allNodes.map((n): RoadmapTreeNode => ({ id: n.id, parentId: n.parentId })),
      dependencyEdges: snapshot.dependencies.map((d) => ({
        id: d.id,
        source: d.blockingItemId,
        target: d.blockedItemId,
      })),
      externalNodes: allExternalNodeInfos.map((n): RoadmapExternalNodeInput => ({ id: n.id })),
      externalEdges: snapshot.externalBlockers.map((b): RoadmapExternalEdgeInput => ({
        id: roadmapCrossEpicEdgeId(blockerKey(b)),
        externalNodeId: roadmapExternalNodeId(b.itemId),
        internalItemId: b.internalItemId,
      })),
    }),
    [allNodes, snapshot.dependencies, snapshot.externalBlockers, allExternalNodeInfos],
  );

  const topologyKey = useMemo(() => buildRoadmapTopologyKey(treeInputAll, collapsed), [treeInputAll, collapsed]);

  const [layout, setLayout] = useState<ElkLayoutResult | null>(null);
  const [fallback, setFallback] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const treeInputVisible: RoadmapTreeInput = {
      nodes: visibleNodes.map((n): RoadmapTreeNode => ({ id: n.id, parentId: n.parentId })),
      dependencyEdges: visibleDependencies.map((d) => ({
        id: d.id,
        source: d.blockingItemId,
        target: d.blockedItemId,
      })),
      externalNodes: visibleExternalNodeInfos.map((n): RoadmapExternalNodeInput => ({ id: n.id })),
      externalEdges: visibleExternalBlockers.map((b): RoadmapExternalEdgeInput => ({
        id: roadmapCrossEpicEdgeId(blockerKey(b)),
        externalNodeId: roadmapExternalNodeId(b.itemId),
        internalItemId: b.internalItemId,
      })),
    };
    setFallback(false);
    computeRoadmapTreeLayout(treeInputVisible)
      .then((result) => {
        if (!cancelled) setLayout(result);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("ELK roadmap layout failed", err);
        setLayout(
          buildFallbackLayout([
            ...visibleNodes.map((n) => n.id),
            ...visibleExternalNodeInfos.map((n) => n.id),
          ]),
        );
        setFallback(true);
      });
    return () => {
      cancelled = true;
    };
    // Re-layout only when topology (incl. collapse state) changes, not on status updates.
  }, [topologyKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleToggleCollapse = useCallback((nodeId: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(nodeId)) next.delete(nodeId);
      else next.add(nodeId);
      return next;
    });
  }, []);

  const { nodes, edges } = useMemo(() => {
    if (!layout) {
      return { nodes: [] as RoadmapFlowNode[], edges: [] as RoadmapFlowEdge[] };
    }

    const flowNodes: Node<RoadmapGraphNodeData, "roadmap">[] = visibleNodes.map((n) => {
      const pos = layout.nodes.get(n.id) ?? { x: 0, y: 0 };
      return {
        id: n.id,
        type: "roadmap",
        position: { x: pos.x, y: pos.y },
        data: {
          label: n.label,
          itemType: n.itemType,
          status: n.status,
          readiness: n.readiness,
          priority: n.priority,
          childCount: childCounts.get(n.id) ?? 0,
          collapsed: collapsed.has(n.id),
          onToggleCollapse: handleToggleCollapse,
        },
      };
    });

    const externalFlowNodes: Node<RoadmapExternalNodeData, "roadmap-external">[] = visibleExternalNodeInfos.map(
      (n) => {
        const pos = layout.nodes.get(n.id) ?? { x: 0, y: 0 };
        return {
          id: n.id,
          type: "roadmap-external",
          position: { x: pos.x, y: pos.y },
          data: {
            title: n.title,
            epicId: n.epicId,
            epicTitle: n.epicTitle,
          },
        };
      },
    );

    const hierarchyEdges: Edge<RoadmapGraphEdgeData, "roadmap-hierarchy">[] = visibleNodes
      .filter((n) => n.parentId !== null)
      .flatMap((n) => {
        const id = roadmapHierarchyEdgeId(n.parentId as string, n.id);
        const route = layout.edges.get(id);
        if (!route) return [];
        return [
          {
            id,
            source: n.parentId as string,
            target: n.id,
            sourceHandle: "source-bottom",
            targetHandle: "target-top",
            type: "roadmap-hierarchy",
            data: { points: route.points },
          } satisfies Edge<RoadmapGraphEdgeData, "roadmap-hierarchy">,
        ];
      });

    // React Flow resolves this markerEnd config into a real <marker> def plus
    // a reference string it hands back to RoadmapDependencyEdge via
    // EdgeProps.markerEnd (see that component) — the color must be resolved
    // to an actual value here (not a CSS var()), since it becomes a literal
    // SVG attribute rather than an inline style.
    const dependencyMarkerColor = resolveStatusColors()["--status-warning"];
    const epicDependencyMarkerColor = resolveStatusColors()["--status-info"];
    // Epic-tier dependencies (an edge where the Epic itself is the blocking or
    // blocked endpoint, not one of its Stories/Tasks — see
    // RoadmapEpicDependencyEdge's doc comment) get their own edge type so
    // they read as visually distinct from an ordinary within-Epic Story/Task
    // dependency, the same way cross-Epic edges are split out below.
    const dependencyEdges: Edge<RoadmapDependencyEdgeData, "roadmap-dependency">[] = [];
    const epicDependencyEdges: Edge<RoadmapEpicDependencyEdgeData, "roadmap-epic-dependency">[] = [];
    for (const d of visibleDependencies) {
      const id = roadmapDependencyEdgeId(d.id);
      const route = layout.edges.get(id);
      if (!route) continue;
      const touchesEpic = d.blockingItemId === snapshot.epic.id || d.blockedItemId === snapshot.epic.id;
      if (touchesEpic) {
        epicDependencyEdges.push({
          id,
          source: d.blockingItemId,
          target: d.blockedItemId,
          sourceHandle: "source-bottom",
          targetHandle: "target-top",
          type: "roadmap-epic-dependency",
          markerEnd: { type: MarkerType.ArrowClosed, color: epicDependencyMarkerColor, width: 18, height: 18 },
          data: { points: route.points },
        } satisfies Edge<RoadmapEpicDependencyEdgeData, "roadmap-epic-dependency">);
      } else {
        dependencyEdges.push({
          id,
          source: d.blockingItemId,
          target: d.blockedItemId,
          sourceHandle: "source-bottom",
          targetHandle: "target-top",
          type: "roadmap-dependency",
          markerEnd: { type: MarkerType.ArrowClosed, color: dependencyMarkerColor, width: 18, height: 18 },
          data: { points: route.points },
        } satisfies Edge<RoadmapDependencyEdgeData, "roadmap-dependency">);
      }
    }

    // Cross-Epic edges (Decision 1). Layout always attaches the external node
    // as a leaf below its in-Epic anchor (internalItemId__source-bottom ->
    // externalNodeId__target-top, see computeRoadmapTreeLayout) — the ELK
    // route below is always in that order regardless of `direction`. The
    // *rendered* edge, though, must point from blocker to blocked (matching
    // the within-Epic dependency edge's convention): for `BLOCKING` the
    // external item is the blocker, so the React Flow edge (and the point
    // order handed to RoadmapCrossEpicEdge) is reversed to run
    // external -> internal instead.
    const crossEpicMarkerColor = resolveStatusColors()["--status-accent"];
    const crossEpicEdges: Edge<RoadmapCrossEpicEdgeData, "roadmap-cross-epic-dependency">[] =
      visibleExternalBlockers.flatMap((b) => {
        const id = roadmapCrossEpicEdgeId(blockerKey(b));
        const route = layout.edges.get(id);
        if (!route) return [];
        const externalNodeId = roadmapExternalNodeId(b.itemId);
        const reversed = b.direction === "BLOCKING";
        const points = reversed ? [...route.points].reverse() : route.points;
        return [
          {
            id,
            source: reversed ? externalNodeId : b.internalItemId,
            target: reversed ? b.internalItemId : externalNodeId,
            sourceHandle: reversed ? "source-top" : "source-bottom",
            targetHandle: reversed ? "target-bottom" : "target-top",
            type: "roadmap-cross-epic-dependency",
            markerEnd: { type: MarkerType.ArrowClosed, color: crossEpicMarkerColor, width: 18, height: 18 },
            data: { points },
          } satisfies Edge<RoadmapCrossEpicEdgeData, "roadmap-cross-epic-dependency">,
        ];
      });

    return {
      nodes: [...flowNodes, ...externalFlowNodes],
      edges: [...hierarchyEdges, ...dependencyEdges, ...epicDependencyEdges, ...crossEpicEdges],
    };
  }, [
    layout,
    snapshot.epic.id,
    visibleNodes,
    visibleDependencies,
    visibleExternalNodeInfos,
    visibleExternalBlockers,
    childCounts,
    collapsed,
    handleToggleCollapse,
  ]);

  const onNodeClick: NodeMouseHandler<RoadmapFlowNode> = useCallback(
    (_event, node) => {
      const detail = findDetailItem(node.id, snapshot);
      onNodeSelect(detail);
    },
    [onNodeSelect, snapshot],
  );
  const onPaneClick = useCallback(() => onNodeSelect(null), [onNodeSelect]);

  return (
    <div
      data-testid="roadmap-graph-container"
      className="relative h-full w-full"
      data-elk-ready={layout && !fallback ? "true" : "false"}
      data-elk-fallback={fallback ? "true" : "false"}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={true}
        minZoom={0.25}
        maxZoom={2}
        proOptions={{ hideAttribution: true }}
      >
        <Controls showInteractive={false} />
        <Background gap={16} size={1} />
      </ReactFlow>
      <RoadmapGraphLegend />
    </div>
  );
}
