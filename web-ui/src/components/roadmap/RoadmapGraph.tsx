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

import type { RoadmapGraphSnapshot } from "@/lib/types";
import { resolveStatusColors } from "@/lib/dagLayout";
import {
  computeRoadmapTreeLayout,
  buildRoadmapTopologyKey,
  roadmapHierarchyEdgeId,
  roadmapDependencyEdgeId,
  ELK_NODE_HEIGHT,
  type ElkLayoutResult,
  type RoadmapTreeInput,
  type RoadmapTreeNode,
} from "@/lib/elkLayout";
import RoadmapGraphNode, { type RoadmapGraphNodeData, type RoadmapItemType } from "./RoadmapGraphNode";
import RoadmapGraphEdge, { type RoadmapGraphEdgeData } from "./RoadmapGraphEdge";
import RoadmapDependencyEdge, { type RoadmapDependencyEdgeData } from "./RoadmapDependencyEdge";
import type { RoadmapDetailItem } from "./RoadmapGraphDetailPanel";

const nodeTypes = { roadmap: RoadmapGraphNode };
const edgeTypes = { "roadmap-hierarchy": RoadmapGraphEdge, "roadmap-dependency": RoadmapDependencyEdge };

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
}

function buildInternalNodes(snapshot: RoadmapGraphSnapshot): InternalNode[] {
  const nodes: InternalNode[] = [
    { id: snapshot.epic.id, itemType: "epic", parentId: null, label: snapshot.epic.title, status: snapshot.epic.status },
  ];
  for (const story of snapshot.stories) {
    nodes.push({ id: story.id, itemType: "story", parentId: story.epicId, label: story.title, status: story.status });
  }
  for (const task of snapshot.tasks) {
    nodes.push({ id: task.id, itemType: "task", parentId: task.storyId, label: task.title, status: task.status });
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

  const treeInputAll: RoadmapTreeInput = useMemo(
    () => ({
      nodes: allNodes.map((n): RoadmapTreeNode => ({ id: n.id, parentId: n.parentId })),
      dependencyEdges: snapshot.dependencies.map((d) => ({
        id: d.id,
        source: d.blockingItemId,
        target: d.blockedItemId,
      })),
    }),
    [allNodes, snapshot.dependencies],
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
    };
    setFallback(false);
    computeRoadmapTreeLayout(treeInputVisible)
      .then((result) => {
        if (!cancelled) setLayout(result);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("ELK roadmap layout failed", err);
        setLayout(buildFallbackLayout(visibleNodes.map((n) => n.id)));
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
      return { nodes: [] as Node<RoadmapGraphNodeData>[], edges: [] as Edge[] };
    }

    const flowNodes: Node<RoadmapGraphNodeData>[] = visibleNodes.map((n) => {
      const pos = layout.nodes.get(n.id) ?? { x: 0, y: 0 };
      return {
        id: n.id,
        type: "roadmap",
        position: { x: pos.x, y: pos.y },
        data: {
          label: n.label,
          itemType: n.itemType,
          status: n.status,
          childCount: childCounts.get(n.id) ?? 0,
          collapsed: collapsed.has(n.id),
          onToggleCollapse: handleToggleCollapse,
        },
      };
    });

    const hierarchyEdges: Edge<RoadmapGraphEdgeData>[] = visibleNodes
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
          } satisfies Edge<RoadmapGraphEdgeData>,
        ];
      });

    // React Flow resolves this markerEnd config into a real <marker> def plus
    // a reference string it hands back to RoadmapDependencyEdge via
    // EdgeProps.markerEnd (see that component) — the color must be resolved
    // to an actual value here (not a CSS var()), since it becomes a literal
    // SVG attribute rather than an inline style.
    const dependencyMarkerColor = resolveStatusColors()["--status-warning"];
    const dependencyEdges: Edge<RoadmapDependencyEdgeData>[] = visibleDependencies.flatMap((d) => {
      const id = roadmapDependencyEdgeId(d.id);
      const route = layout.edges.get(id);
      if (!route) return [];
      return [
        {
          id,
          source: d.blockingItemId,
          target: d.blockedItemId,
          sourceHandle: "source-bottom",
          targetHandle: "target-top",
          type: "roadmap-dependency",
          markerEnd: { type: MarkerType.ArrowClosed, color: dependencyMarkerColor, width: 18, height: 18 },
          data: { points: route.points },
        } satisfies Edge<RoadmapDependencyEdgeData>,
      ];
    });

    return { nodes: flowNodes, edges: [...hierarchyEdges, ...dependencyEdges] };
  }, [layout, visibleNodes, visibleDependencies, childCounts, collapsed, handleToggleCollapse]);

  const onNodeClick: NodeMouseHandler<Node<RoadmapGraphNodeData>> = useCallback(
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
      className="h-full w-full"
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
    </div>
  );
}
