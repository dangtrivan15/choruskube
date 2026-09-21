import "@xyflow/react/dist/style.css";
import { useCallback, useEffect, useMemo, useState } from "react";
import { ReactFlow, Background, Controls, MarkerType, type Connection, type Edge, type Node } from "@xyflow/react";
import RoadmapGraphNode, { type RoadmapGraphNodeData } from "@/components/roadmap/RoadmapGraphNode";
import RoadmapGraphEdge, { type RoadmapGraphEdgeData } from "@/components/roadmap/RoadmapGraphEdge";
import RoadmapDependencyEdge, { type RoadmapDependencyEdgeData } from "@/components/roadmap/RoadmapDependencyEdge";
import { resolveStatusColors } from "@/lib/dagLayout";
import {
  computeRoadmapTreeLayout,
  buildRoadmapTopologyKey,
  roadmapHierarchyEdgeId,
  ELK_NODE_HEIGHT,
  type ElkLayoutResult,
  type RoadmapTreeInput,
} from "@/lib/elkLayout";
import {
  candidateDocToGraph,
  connectCandidateNodes,
  removeCandidateDependency,
} from "@/lib/roadmapCandidateGraph";
import { useActivityFeed } from "@/hooks/useActivityFeed";
import { showMutationToast } from "@/lib/toast-messages";
import type { RoadmapCandidatesDocument } from "@/lib/types";
import { ROADMAP_EDGE_STYLES } from "@/lib/roadmapEdgeStyles";

// Declared at module scope so xyflow doesn't warn about a new nodeTypes/edgeTypes
// object every render. Only the two edge kinds a candidate graph draws — hierarchy
// (Epic→Story→Task) and dependency (dashed, warning).
const nodeTypes = { roadmap: RoadmapGraphNode };
const edgeTypes = {
  "roadmap-hierarchy": RoadmapGraphEdge,
  "roadmap-dependency": RoadmapDependencyEdge,
};

function buildFallbackLayout(nodeIds: string[]): ElkLayoutResult {
  const nodes = new Map(nodeIds.map((id, idx) => [id, { x: 0, y: idx * (ELK_NODE_HEIGHT + 30) }] as const));
  return { nodes, edges: new Map() };
}

interface Props {
  value: RoadmapCandidatesDocument;
  onChange: (next: RoadmapCandidatesDocument) => void;
}

/**
 * Graph view of a pre-materialization roadmap proposal: the Epic→Story→Task
 * forest plus proposed dependency edges. Editing is dependency-only — drag
 * between two nodes to add a blocker (guarded against cycles and unkeyed
 * endpoints), select an edge and delete to remove one; ticket-field edits stay
 * in the card breakdown. Both surfaces share one `value`/`onChange` document.
 */
export default function RoadmapCandidateGraph({ value, onChange }: Props) {
  const { addEntry } = useActivityFeed();
  const model = useMemo(() => candidateDocToGraph(value), [value]);

  const treeInput: RoadmapTreeInput = useMemo(
    () => ({
      nodes: model.nodes.map((n) => ({ id: n.id, parentId: n.parentId })),
      dependencyEdges: model.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })),
    }),
    [model],
  );
  const topologyKey = useMemo(() => buildRoadmapTopologyKey(treeInput, new Set()), [treeInput]);

  const [layout, setLayout] = useState<ElkLayoutResult | null>(null);
  const [fallback, setFallback] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setFallback(false);
    computeRoadmapTreeLayout(treeInput)
      .then((result) => {
        if (!cancelled) setLayout(result);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("ELK candidate layout failed", err);
        setLayout(buildFallbackLayout(model.nodes.map((n) => n.id)));
        setFallback(true);
      });
    return () => {
      cancelled = true;
    };
    // Re-layout only on shape changes (topologyKey), not on label/priority edits.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [topologyKey]);

  const flowNodes = useMemo<Node<RoadmapGraphNodeData, "roadmap">[]>(() => {
    if (!layout) return [];
    return model.nodes.map((n) => {
      const pos = layout.nodes.get(n.id) ?? { x: 0, y: 0 };
      return {
        id: n.id,
        type: "roadmap",
        position: { x: pos.x, y: pos.y },
        // Candidates have no lifecycle status yet — "backlog" reads as "not started"
        // and maps to the node's neutral color token.
        data: { label: n.label, itemType: n.itemType, status: "backlog", priority: n.priority },
      };
    });
  }, [layout, model]);

  const flowEdges = useMemo(() => {
    if (!layout) return [];
    const dependencyColor = resolveStatusColors()[ROADMAP_EDGE_STYLES.dependency.token];
    const hierarchy = model.nodes.flatMap((n) => {
      if (!n.parentId) return [];
      const id = roadmapHierarchyEdgeId(n.parentId, n.id);
      const route = layout.edges.get(id);
      return [
        {
          id,
          source: n.parentId,
          target: n.id,
          sourceHandle: "source-bottom",
          targetHandle: "target-top",
          type: "roadmap-hierarchy",
          deletable: false,
          data: { points: route?.points },
        } satisfies Edge<RoadmapGraphEdgeData, "roadmap-hierarchy">,
      ];
    });
    const dependency = model.edges.map(
      (e) =>
        ({
          id: e.id,
          source: e.source,
          target: e.target,
          sourceHandle: "source-bottom",
          targetHandle: "target-top",
          type: "roadmap-dependency",
          deletable: true,
          markerEnd: { type: MarkerType.ArrowClosed, color: dependencyColor, width: 18, height: 18 },
          data: { points: layout.edges.get(e.id)?.points },
        }) satisfies Edge<RoadmapDependencyEdgeData, "roadmap-dependency">,
    );
    return [...hierarchy, ...dependency];
  }, [layout, model]);

  const onConnect = useCallback(
    (connection: Connection) => {
      if (!connection.source || !connection.target) return;
      const result = connectCandidateNodes(model, value, connection.source, connection.target);
      if (result.doc) {
        onChange(result.doc);
        return;
      }
      addEntry(
        showMutationToast(
          result.error === "cycle"
            ? "That dependency would create a cycle"
            : "Only items with a key can have dependencies",
          "warning",
        ),
      );
    },
    [model, value, onChange, addEntry],
  );

  const onEdgesDelete = useCallback(
    (deleted: Edge[]) => {
      const byId = new Map(model.edges.map((e) => [e.id, e] as const));
      let next = value;
      for (const edge of deleted) {
        const modelEdge = byId.get(edge.id);
        if (modelEdge) next = removeCandidateDependency(next, modelEdge.blocking, modelEdge.blocked);
      }
      if (next !== value) onChange(next);
    },
    [model, value, onChange],
  );

  if (model.nodes.length === 0) {
    return (
      <div data-testid="roadmap-candidate-graph" className="text-sm text-muted-foreground">
        No proposed items to graph.
      </div>
    );
  }

  return (
    <div
      data-testid="roadmap-candidate-graph"
      data-elk-ready={layout != null && !fallback ? "true" : "false"}
      className="h-[420px] w-full rounded-md border"
    >
      <ReactFlow
        nodes={flowNodes}
        edges={flowEdges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onConnect={onConnect}
        onEdgesDelete={onEdgesDelete}
        nodesDraggable={false}
        nodesConnectable
        elementsSelectable
        fitView
        fitViewOptions={{ padding: 0.2 }}
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
