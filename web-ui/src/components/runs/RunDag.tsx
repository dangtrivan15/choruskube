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

/**
 * The execution that paged the Supervisor: the most recently completed execution whose decision
 * is `escalate`. Selection is by `completedAt`, not array position or iteration — the Supervisor
 * is re-entered many times per run by design, `run.nodeExecutions` carries no ordering guarantee
 * (the repository lookup behind it has no `ORDER BY`), and the relevant escalation is the one
 * that just happened, not the first one Postgres happens to return. Mirrors the api-server's
 * `ArtifactResolutionService.resolveEscalatingExecution`, which answers this identical question
 * for the escalation gate panel, so the two surfaces agree on which execution escalated.
 * `completedAt` sorts as oldest when null, matching this directory's existing
 * `DetailPanel.tsx`#`findTriggerDecision` precedent for the same null-handling choice.
 */
function resolveEscalatingExecution(
  executions: NodeExecutionResponse[],
): NodeExecutionResponse | undefined {
  return [...executions]
    .filter((e) => e.decision === "escalate")
    .sort((a, b) => {
      const at = a.completedAt ? new Date(a.completedAt).getTime() : 0;
      const bt = b.completedAt ? new Date(b.completedAt).getTime() : 0;
      return bt - at;
    })[0];
}

/**
 * The Supervisor: a template's single edgeless routing hub. It is deliberately excluded from
 * the ELK layout below — it is not part of the happy path — and pinned beside the graph instead.
 */
function isRoutingHub(node: GraphSnapshot["nodes"][number]): boolean {
  return node.config_overrides?.routing_hub === true;
}

/**
 * A Supervisor connection. Uses React Flow's default bezier edge, not the project's custom
 * `dag` type — `dag` renders an ELK-computed route, and these edges have none by construction,
 * since the Supervisor fires no edges of its own.
 */
function synthEdge(source: string, target: string, label: string): Edge<DagEdgeData> {
  return {
    id: `supervisor:${source}->${target}`,
    source,
    target,
    label,
    animated: true,
    className: "supervisor-edge",
    style: { strokeDasharray: "6 4" },
  };
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

  // Fed to all three layout consumers below (topologyKey, computeElkLayout, buildFallbackLayout)
  // so none of them ever sees the Supervisor — it is pinned beside the graph instead (see the
  // `hub` block in the nodes/edges memo further down), not laid out as a step within it.
  const laidOutSnapshot = useMemo<GraphSnapshot | null>(
    () => (snapshot ? { ...snapshot, nodes: snapshot.nodes.filter((n) => !isRoutingHub(n)) } : null),
    [snapshot],
  );

  const topologyKey = useMemo(() => buildTopologyKey(laidOutSnapshot), [laidOutSnapshot]);

  const [layout, setLayout] = useState<ElkLayoutResult | null>(null);
  const [fallback, setFallback] = useState(false);

  useEffect(() => {
    if (!laidOutSnapshot) {
      setLayout(null);
      setFallback(false);
      return;
    }
    let cancelled = false;
    setFallback(false);
    computeElkLayout(laidOutSnapshot)
      .then((result) => {
        if (!cancelled) setLayout(result);
      })
      .catch((err) => {
        if (cancelled) return;
        console.error("ELK layout failed", err);
        setLayout(buildFallbackLayout(laidOutSnapshot));
        setFallback(true);
      });
    return () => {
      cancelled = true;
    };
    // Re-layout only when topology changes — not on status updates.
  }, [topologyKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const { nodes, edges } = useMemo(() => {
    if (!snapshot || !laidOutSnapshot) {
      return { nodes: [] as Node<DagNodeData>[], edges: [] as Edge<DagEdgeData>[] };
    }
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
    const flowNodes: Node<DagNodeData>[] = laidOutSnapshot.nodes.map((sn) => {
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

    // The Supervisor: pinned beside the laid-out graph rather than positioned as a step in it.
    // Searched for across the FULL snapshot (not laidOutSnapshot) — it was filtered out above.
    const hub = snapshot.nodes.find(isRoutingHub);
    if (hub) {
      const xs = [...layout.nodes.values()].map((p) => p.x);
      const ys = [...layout.nodes.values()].map((p) => p.y);
      const hubPos = {
        x: (xs.length ? Math.max(...xs) : 0) + 280,
        y: ys.length ? (Math.min(...ys) + Math.max(...ys)) / 2 : 0,
      };
      const hubExec = findLatestExecution(run.nodeExecutions, hub.template_node_id);
      flowNodes.push({
        id: hub.template_node_id,
        type: "dag",
        position: hubPos,
        data: {
          label: hub.label,
          executorType: hub.executor_type,
          status: hubExec?.status ?? "pending",
          iteration: hubExec?.iteration ?? 0,
          isRoutingHub: true,
        },
      });

      // The Supervisor fires no edges, so there is nothing in traversedEdgeIds to highlight.
      // Draw its two connections from the decision strings instead: whoever decided `escalate`
      // reached it, and its own route:<label> decision names where it sent the run.
      const escalator = resolveEscalatingExecution(run.nodeExecutions);
      if (escalator) {
        flowEdges.push(synthEdge(escalator.templateNodeId, hub.template_node_id, "escalate"));
      }
      if (hubExec?.decision?.startsWith("route:")) {
        const routedLabel = hubExec.decision.slice("route:".length);
        const routedNode = snapshot.nodes.find((n) => n.label === routedLabel);
        if (routedNode) {
          flowEdges.push(synthEdge(hub.template_node_id, routedNode.template_node_id, hubExec.decision));
        }
      }
    }

    return { nodes: flowNodes, edges: flowEdges };
  }, [layout, run.nodeExecutions, snapshot, laidOutSnapshot]);

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
