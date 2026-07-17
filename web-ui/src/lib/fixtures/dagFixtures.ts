import type { GraphSnapshot, NodeExecutionResponse, RunResponse, SnapshotEdge } from "@/lib/types";

const ts = "2026-05-04T00:00:00Z";

/**
 * Each entry is either a status string (decision defaults to `null`) or a
 * `{ status, decision }` tuple — the decision drives which outgoing edges
 * "fired" in the simulated run, mirroring the orchestrator's rule so the
 * playground can render highlighted edges without the real backend.
 */
type NodeState = string | { status: string; decision?: string | null };

/** Fixture authoring shape: edges omit `template_edge_id` (synthesized below). */
type FixtureEdge = Omit<SnapshotEdge, "template_edge_id">;
type FixtureSnapshot = {
  nodes: GraphSnapshot["nodes"];
  edges: FixtureEdge[];
};

/** Stable synthetic edge id used by fixtures (real edges are uuids). */
function edgeId(e: FixtureEdge): string {
  return `e-${e.source_node_id}-${e.condition ?? "_null_"}-${e.target_node_id}`;
}

/**
 * Mirrors `orchestrator/internal/workflow/graph.go EvaluateEdges` for a single
 * source: unconditional edges always fire, conditional edges fire when the
 * decision matches (case-insensitive). Fixture-only — production never
 * re-derives this rule.
 */
function firedEdgeIdsFor(
  source: string,
  decision: string | null,
  edges: FixtureEdge[],
): string[] {
  const outgoing = edges.filter((e) => e.source_node_id === source);
  if (outgoing.length === 0) return [];
  const dec = decision?.toLowerCase() ?? "";
  return outgoing
    .filter((e) => e.condition === null || e.condition.toLowerCase() === dec)
    .map(edgeId);
}

function snapshotRun(
  name: string,
  snapshot: FixtureSnapshot,
  executionsByNode: Record<string, NodeState>,
): RunResponse {
  // Inject stable synthetic IDs into the snapshot edges so the simulated
  // `traversedEdgeIds` below references them.
  const edgesWithIds: SnapshotEdge[] = snapshot.edges.map((e) => ({ ...e, template_edge_id: edgeId(e) }));
  const snapshotWithIds: GraphSnapshot = { nodes: snapshot.nodes, edges: edgesWithIds };

  const nodeExecutions: NodeExecutionResponse[] = snapshot.nodes.map((n) => {
    const raw = executionsByNode[n.template_node_id];
    const state: { status: string; decision?: string | null } =
      typeof raw === "string" ? { status: raw } : (raw ?? { status: "pending" });
    const traversedEdgeIds =
      state.status === "completed" ? firedEdgeIdsFor(n.template_node_id, state.decision ?? null, edgesWithIds) : null;
    return {
      id: `exec-${n.template_node_id}`,
      templateNodeId: n.template_node_id,
      status: state.status,
      result: null,
      decision: state.decision ?? null,
      podName: null,
      iteration: 0,
      startedAt: null,
      completedAt: null,
      errorMessage: null,
      graphVersion: 1,
      artifactRefs: "{}",
      label: n.label,
      loopGroup: null,
      reviewerType: null,
      traversedEdgeIds,
      requiredArtifacts: null,
    };
  });

  return {
    id: `run-${name}`,
    graphTemplateId: `tpl-${name}`,
    templateName: name,
    name,
    status: "running",
    externalRunId: name,
    graphVersion: 1,
    graphSnapshot: snapshotWithIds,
    startedAt: ts,
    completedAt: null,
    createdAt: ts,
    nodeExecutions,
    pullRequests: [],
    promptText: null,
    task: null,
    softwareProject: null,
  };
}

const FEATURE_DEVELOPMENT: FixtureSnapshot = {
  nodes: [
    { template_node_id: "draft_spec_and_plan",    label: "draft_spec_and_plan",    executor_type: "ai",    is_entrypoint: true  },
    { template_node_id: "spec_review",            label: "spec_review",            executor_type: "ai",    is_entrypoint: false },
    { template_node_id: "approve_spec_and_plan",  label: "approve_spec_and_plan",  executor_type: "human", is_entrypoint: false },
    { template_node_id: "implement",              label: "implement",              executor_type: "ai",    is_entrypoint: false },
    { template_node_id: "code_review",            label: "code_review",            executor_type: "ai",    is_entrypoint: false },
    { template_node_id: "test",                   label: "test",                   executor_type: "ai",    is_entrypoint: false },
    { template_node_id: "final_approval",         label: "final_approval",         executor_type: "human", is_entrypoint: false },
    { template_node_id: "push_create_pr",         label: "push_create_pr",         executor_type: "ai",    is_entrypoint: false },
  ],
  edges: [
    // Spec subgraph
    { source_node_id: "draft_spec_and_plan",   target_node_id: "spec_review",           condition: null },
    { source_node_id: "spec_review",           target_node_id: "approve_spec_and_plan", condition: "approved" },
    { source_node_id: "spec_review",           target_node_id: "approve_spec_and_plan", condition: "need_human_decision:alternative_proposal" },
    { source_node_id: "spec_review",           target_node_id: "approve_spec_and_plan", condition: "need_human_decision:iteration_cap" },
    { source_node_id: "spec_review",           target_node_id: "approve_spec_and_plan", condition: "need_human_decision:uncertainty" },
    { source_node_id: "spec_review",           target_node_id: "spec_review",           condition: "revised" },
    { source_node_id: "approve_spec_and_plan", target_node_id: "implement",             condition: "approved" },
    { source_node_id: "approve_spec_and_plan", target_node_id: "spec_review",           condition: "rereview" },
    { source_node_id: "approve_spec_and_plan", target_node_id: "draft_spec_and_plan",   condition: "redraft" },
    // Impl subgraph
    { source_node_id: "implement",    target_node_id: "code_review",  condition: null },
    { source_node_id: "code_review",  target_node_id: "code_review",  condition: "revised" },
    { source_node_id: "code_review",  target_node_id: "test",         condition: "approved" },
    { source_node_id: "code_review",  target_node_id: "test",         condition: "need_human_decision:iteration_cap" },
    { source_node_id: "code_review",  target_node_id: "test",         condition: "need_human_decision:uncertainty" },
    { source_node_id: "test",         target_node_id: "final_approval", condition: "passed" },
    { source_node_id: "test",         target_node_id: "implement",      condition: "failed" },
    { source_node_id: "final_approval", target_node_id: "push_create_pr", condition: "approved" },
    { source_node_id: "final_approval", target_node_id: "code_review",   condition: "rereview" },
  ],
};

const SPARSE_CHAIN: FixtureSnapshot = {
  nodes: [
    { template_node_id: "n1", label: "Step 1", executor_type: "ai", is_entrypoint: true  },
    { template_node_id: "n2", label: "Step 2", executor_type: "ai", is_entrypoint: false },
    { template_node_id: "n3", label: "Step 3", executor_type: "ai", is_entrypoint: false },
    { template_node_id: "n4", label: "Step 4", executor_type: "ai", is_entrypoint: false },
  ],
  edges: [
    { source_node_id: "n1", target_node_id: "n2", condition: null },
    { source_node_id: "n2", target_node_id: "n3", condition: null },
    { source_node_id: "n3", target_node_id: "n4", condition: null },
  ],
};

const DENSE_FANOUT: FixtureSnapshot = {
  nodes: [
    { template_node_id: "root", label: "Root", executor_type: "ai", is_entrypoint: true  },
    { template_node_id: "a",    label: "A",    executor_type: "ai", is_entrypoint: false },
    { template_node_id: "b",    label: "B",    executor_type: "ai", is_entrypoint: false },
    { template_node_id: "c",    label: "C",    executor_type: "ai", is_entrypoint: false },
    { template_node_id: "d",    label: "D",    executor_type: "ai", is_entrypoint: false },
    { template_node_id: "join", label: "Join", executor_type: "ai", is_entrypoint: false },
  ],
  edges: [
    { source_node_id: "root", target_node_id: "a", condition: "branch_a" },
    { source_node_id: "root", target_node_id: "b", condition: "branch_b" },
    { source_node_id: "root", target_node_id: "c", condition: "branch_c" },
    { source_node_id: "root", target_node_id: "d", condition: "branch_d" },
    { source_node_id: "a",    target_node_id: "join", condition: null },
    { source_node_id: "b",    target_node_id: "join", condition: null },
    { source_node_id: "c",    target_node_id: "join", condition: null },
    { source_node_id: "d",    target_node_id: "join", condition: null },
  ],
};

export const DAG_FIXTURES = {
  feature_development: snapshotRun("feature_development", FEATURE_DEVELOPMENT, {
    // Decisions reflect the path actually taken to reach the awaiting_human
    // state on Final Approval: spec→review→implement→code_review→test→final.
    draft_spec_and_plan: "completed",
    // Use need_human_decision:iteration_cap so the escalation edge to
    // approve_spec_and_plan highlights correctly.
    spec_review: { status: "completed", decision: "need_human_decision:iteration_cap" },
    approve_spec_and_plan: { status: "completed", decision: "approved" },
    // Implement → code_review is unconditional; no decision value needed.
    implement: { status: "completed", decision: null },
    // code_review → test fires on "approved"
    code_review: { status: "completed", decision: "approved" },
    test: { status: "completed", decision: "passed" },
    final_approval: "awaiting_human",
    push_create_pr: "pending",
  }),
  sparse_chain: snapshotRun("sparse_chain", SPARSE_CHAIN, {}),
  dense_fanout: snapshotRun("dense_fanout", DENSE_FANOUT, {}),
} as const;

export type DagFixtureKey = keyof typeof DAG_FIXTURES;
