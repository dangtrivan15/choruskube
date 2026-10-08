import type { RunResponse, NodeExecutionResponse, SnapshotNode } from "@/lib/types";

/** Nodes needing attention (awaiting-human first, then failed) and nodes currently running. */
export interface ActiveNodeClassification {
  attention: SnapshotNode[];
  running: SnapshotNode[];
}

const EMPTY_CLASSIFICATION: ActiveNodeClassification = { attention: [], running: [] };

/** The latest execution per `templateNodeId`, keyed by highest `iteration`. */
function latestExecutionsByNode(
  executions: NodeExecutionResponse[],
): Map<string, NodeExecutionResponse> {
  const latest = new Map<string, NodeExecutionResponse>();
  for (const exec of executions) {
    const current = latest.get(exec.templateNodeId);
    if (!current || exec.iteration > current.iteration) {
      latest.set(exec.templateNodeId, exec);
    }
  }
  return latest;
}

/**
 * Classifies every snapshot node (including the routing hub) by its latest
 * execution's status. `attention` orders awaiting-human nodes before failed
 * ones — both in snapshot order — so `attention[0]` is always the same node
 * `pickFocusNode` picks: the "Review …" button, the empty-state shortcut list,
 * and auto-focus all read off this one ordering.
 */
export function classifyActiveNodes(run: RunResponse): ActiveNodeClassification {
  const snapshot = run.graphSnapshot;
  if (!snapshot) return EMPTY_CLASSIFICATION;

  const latest = latestExecutionsByNode(run.nodeExecutions);
  const awaitingHuman: SnapshotNode[] = [];
  const failed: SnapshotNode[] = [];
  const running: SnapshotNode[] = [];

  for (const node of snapshot.nodes) {
    const status = latest.get(node.template_node_id)?.status;
    if (status === "awaiting_human") awaitingHuman.push(node);
    else if (status === "failed") failed.push(node);
    else if (status === "running") running.push(node);
  }

  return { attention: [...awaitingHuman, ...failed], running };
}

/** The node the page should auto-focus or offer a "Review …"/"Failed: …" shortcut for, if any. */
export function pickFocusNode(run: RunResponse): string | null {
  const { attention, running } = classifyActiveNodes(run);
  return attention[0]?.template_node_id ?? running[0]?.template_node_id ?? null;
}

/**
 * Which of the two attention kinds a given node is currently in — distinguishes the
 * "Review …" (awaiting_human) wording from "Failed: …", which `classifyActiveNodes`'s
 * single merged `attention` list (ordered, not tagged) doesn't carry on its own.
 */
export function attentionKind(run: RunResponse, templateNodeId: string): "awaiting_human" | "failed" | null {
  const status = latestExecutionsByNode(run.nodeExecutions).get(templateNodeId)?.status;
  return status === "awaiting_human" || status === "failed" ? status : null;
}
