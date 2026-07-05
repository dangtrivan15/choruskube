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

import type { RunResponse, NodeExecutionResponse, GraphSnapshot } from "@/lib/types";
import { getEdgeColor } from "@/lib/dagLayout";
import {
  computeElkLayout,
  elkEdgeId,
  ELK_NODE_HEIGHT,
  ELK_NODE_WIDTH,
  type ElkLayoutResult,
  type ElkPoint,
} from "@/lib/elkLayout";
import DagNode, { type DagNodeData } from "./DagNode";
import DagEdge, { type DagEdgeData } from "./DagEdge";

interface RunDagProps {
  run: RunResponse;
  onNodeSelect: (nodeId: string | null) => void;
}

const nodeTypes = { dag: DagNode };
const edgeTypes = { dag: DagEdge };

/** Statuses considered "active" for edge highlighting — must match DagNode's pulse set. */
const ACTIVE_EDGE_TARGET_STATUSES = new Set(["running", "awaiting_human", "live_chat"]);

/** Edge conditions that semantically represent a failure / rejection branch. */
const NEGATIVE_EDGE_CONDITIONS = new Set(["rejected", "failed"]);

function findLatestExecution(
  executions: NodeExecutionResponse[],
  templateNodeId: string,
): NodeExecutionResponse | undefined {
  let latest: NodeExecutionResponse | undefined;
  for (const exec of executions) {
    if (exec.templateNodeId === templateNodeId) {
      if (!latest || exec.iteration > latest.iteration) latest = exec;
    }
  }
  return latest;
}

/** Stable hash of graph topology — recomputed only on snapshot structure change. */
function buildTopologyKey(snapshot: GraphSnapshot | null): string {
  if (!snapshot) return "empty";
  const nodes = snapshot.nodes
    .map((n) => `${n.template_node_id}`)
    .sort()
    .join("|");
  const edges = snapshot.edges
    .map((e) => `${e.source_node_id}->${e.target_node_id}:${e.condition ?? ""}`)
    .sort()
    .join("|");
  return `${nodes}#${edges}`;
}

/**
 * Fallback layout when ELK fails: nodes stacked vertically by index, straight
 * edges between node centres. Intentionally ugly — signals to a developer that
 * something went wrong with ELK. Authored positions are no longer available
 * since position_x/y were removed from the snapshot.
 */
function buildFallbackLayout(snapshot: GraphSnapshot): ElkLayoutResult {
  const nodes = new Map(
    snapshot.nodes.map((n, idx) => [n.template_node_id, { x: 0, y: idx * (ELK_NODE_HEIGHT + 30) }] as const),
  );
  const edges = new Map(
    snapshot.edges.map((e) => {
      const srcPos = nodes.get(e.source_node_id) ?? { x: 0, y: 0 };
      const tgtPos = nodes.get(e.target_node_id) ?? { x: 0, y: 0 };
      const points: ElkPoint[] = [
        { x: srcPos.x + ELK_NODE_WIDTH / 2, y: srcPos.y + ELK_NODE_HEIGHT / 2 },
        { x: tgtPos.x + ELK_NODE_WIDTH / 2, y: tgtPos.y + ELK_NODE_HEIGHT / 2 },
      ];
      return [elkEdgeId(e.source_node_id, e.target_node_id, e.condition), { points }] as const;
    }),
  );
  return { nodes, edges };
}

export default function RunDag({ run, onNodeSelect }: RunDagProps) {
  const snapshot = run.graphSnapshot;
  const topologyKey = useMemo(() => buildTopologyKey(snapshot), [snapshot]);

  const [layout, setLayout] = useState<ElkLayoutResult | null>(null);
  const [fallback, setFallback] = useState(false);

  useEffect(() => {
    if (!snapshot) {
      setLayout(null);
      setFallback(false);
      return;
    }
    let cancelled = false;
    setFallback(false);
    computeElkLayout(snapshot)
      .then((result) => {
        if (!cancelled) setLayout(result);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("ELK layout failed", err);
        setLayout(buildFallbackLayout(snapshot));
        setFallback(true);
      });
    return () => {
      cancelled = true;
    };
    // Re-layout only when topology changes — not on status updates.
  }, [topologyKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const { nodes, edges } = useMemo(() => {
    if (!snapshot) return { nodes: [] as Node<DagNodeData>[], edges: [] as Edge<DagEdgeData>[] };
    if (!layout) {
      // Pre-layout: ELK promise not yet resolved (~100 ms). Render an empty canvas
      // so there is no misleading flash of incorrectly positioned nodes.
      return { nodes: [] as Node<DagNodeData>[], edges: [] as Edge<DagEdgeData>[] };
    }

    // Build per-source-template-node sets of fired edge IDs from the latest
    // execution of each node. The orchestrator persists `traversedEdgeIds`
    // when it fires edges; the UI never re-derives the rule. `null` (row
    // predates V55, or node still running) is treated as "no edges fired".
    const statusByNode = new Map<string, string>();
    const firedEdgesBySource = new Map<string, Set<string>>();
    const flowNodes: Node<DagNodeData>[] = snapshot.nodes.map((sn) => {
      const exec = findLatestExecution(run.nodeExecutions, sn.template_node_id);
      const status = exec?.status ?? "pending";
      statusByNode.set(sn.template_node_id, status);
      if (exec?.traversedEdgeIds) {
        firedEdgesBySource.set(sn.template_node_id, new Set(exec.traversedEdgeIds));
      }
      const pos = layout.nodes.get(sn.template_node_id) ?? { x: 0, y: 0 };
      return {
        id: sn.template_node_id,
        type: "dag",
        position: { x: pos.x, y: pos.y },
        data: { label: sn.label, executorType: sn.executor_type, status, iteration: exec?.iteration ?? 0 },
      };
    });

    const activeNodeIds = new Set<string>();
    for (const [id, status] of statusByNode) {
      if (ACTIVE_EDGE_TARGET_STATUSES.has(status)) activeNodeIds.add(id);
    }

    const flowEdges: Edge<DagEdgeData>[] = snapshot.edges
      .flatMap((se): Edge<DagEdgeData>[] => {
        const id = elkEdgeId(se.source_node_id, se.target_node_id, se.condition);
        const route = layout.edges.get(id);
        if (!route) return [];
        const targetStatus = statusByNode.get(se.target_node_id) ?? "pending";
        const targetActive = activeNodeIds.has(se.target_node_id);
        const edgeFired = firedEdgesBySource.get(se.source_node_id)?.has(se.template_edge_id) ?? false;
        const isRunningEdge = targetActive && edgeFired;
        const isNegative = NEGATIVE_EDGE_CONDITIONS.has(se.condition ?? "");
        // Colour rule:
        //   - fired + negative condition  → red (--status-error)
        //   - fired + active target       → target's status colour (animated)
        //   - everything else             → muted neutral
        // Animation (`isActive`) is independent of colour, so a fired edge
        // whose condition is rejected/failed animates *in red*.
        const effectiveStatus =
          edgeFired && isNegative ? "failed" : isRunningEdge ? targetStatus : "pending";
        const edge: Edge<DagEdgeData> = {
          id,
          source: se.source_node_id,
          target: se.target_node_id,
          sourceHandle: "source-bottom",
          targetHandle: "target-top",
          type: "dag",
          markerEnd: {
            type: MarkerType.ArrowClosed,
            color: getEdgeColor(effectiveStatus),
            width: 20,
            height: 20,
          },
          data: {
            condition: se.condition,
            effectiveStatus,
            isActive: isRunningEdge,
            points: route.points,
            labelPosition: route.label
              ? { x: route.label.x + route.label.width / 2, y: route.label.y + route.label.height / 2 }
              : undefined,
          },
        };
        return [edge];
      });

    return { nodes: flowNodes, edges: flowEdges };
  }, [layout, run.nodeExecutions, snapshot]);

  const onNodeClick: NodeMouseHandler<Node<DagNodeData>> = useCallback(
    (_event, node) => onNodeSelect(node.id),
    [onNodeSelect],
  );
  const onPaneClick = useCallback(() => onNodeSelect(null), [onNodeSelect]);

  return (
    <div
      data-testid="run-dag-container"
      className="h-full w-full"
      data-elk-ready={layout && !fallback ? "true" : "false"}
      data-elk-fallback={fallback ? "true" : "false"}
    >
      <style>{`
        @keyframes dagEdgeDash {
          to { stroke-dashoffset: -10; }
        }
      `}</style>
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
