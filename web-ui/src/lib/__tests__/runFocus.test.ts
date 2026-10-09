import { describe, it, expect } from "vitest";
import { classifyActiveNodes, pickFocusNode } from "../runFocus";
import type { GraphSnapshot, NodeExecutionResponse, RunResponse } from "@/lib/types";

function makeExecution(
  overrides: Partial<NodeExecutionResponse> & { templateNodeId: string; status: string },
): NodeExecutionResponse {
  return {
    id: `exec-${overrides.templateNodeId}-${overrides.status}-${overrides.iteration ?? 1}`,
    result: null,
    decision: null,
    podName: null,
    iteration: 1,
    startedAt: null,
    completedAt: null,
    errorMessage: null,
    graphVersion: 1,
    artifactRefs: "{}",
    label: null,
    loopGroup: null,
    reviewerType: null,
    traversedEdgeIds: null,
    requiredArtifacts: null,
    candidateBreakdown: null,
    ...overrides,
  };
}

function makeSnapshot(nodeIds: string[]): GraphSnapshot {
  return {
    nodes: nodeIds.map((id) => ({
      template_node_id: id,
      label: id,
      executor_type: "ai",
      is_entrypoint: false,
    })),
    edges: [],
  };
}

function makeRun(overrides: Partial<RunResponse> = {}): RunResponse {
  return {
    id: "run-1",
    graphTemplateId: "tpl-1",
    templateName: "Test",
    name: null,
    status: "running",
    externalRunId: "ext-1",
    graphVersion: 1,
    graphSnapshot: null,
    startedAt: null,
    completedAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    nodeExecutions: [],
    pullRequests: [],
    promptText: null,
    task: null,
    autopilotId: null,
    softwareProject: null,
    ...overrides,
  };
}

describe("classifyActiveNodes / pickFocusNode", () => {
  it("prioritizes awaiting_human over failed over running", () => {
    const run = makeRun({
      graphSnapshot: makeSnapshot(["a", "b", "c"]),
      nodeExecutions: [
        makeExecution({ templateNodeId: "a", status: "running" }),
        makeExecution({ templateNodeId: "b", status: "failed" }),
        makeExecution({ templateNodeId: "c", status: "awaiting_human" }),
      ],
    });

    const { attention, running } = classifyActiveNodes(run);
    expect(attention.map((n) => n.template_node_id)).toEqual(["c", "b"]);
    expect(running.map((n) => n.template_node_id)).toEqual(["a"]);
    expect(pickFocusNode(run)).toBe("c");
  });

  it("breaks ties within a status by snapshot order", () => {
    const run = makeRun({
      graphSnapshot: makeSnapshot(["a", "b"]),
      nodeExecutions: [
        makeExecution({ templateNodeId: "b", status: "awaiting_human" }),
        makeExecution({ templateNodeId: "a", status: "awaiting_human" }),
      ],
    });

    expect(classifyActiveNodes(run).attention.map((n) => n.template_node_id)).toEqual(["a", "b"]);
  });

  it("keeps the gate first in attention even when a failed node precedes it in snapshot order", () => {
    const run = makeRun({
      graphSnapshot: makeSnapshot(["failed_node", "gate_node"]),
      nodeExecutions: [
        makeExecution({ templateNodeId: "failed_node", status: "failed" }),
        makeExecution({ templateNodeId: "gate_node", status: "awaiting_human" }),
      ],
    });

    const { attention } = classifyActiveNodes(run);
    expect(attention[0].template_node_id).toBe("gate_node");
    expect(pickFocusNode(run)).toBe(attention[0].template_node_id);
  });

  it("uses the latest iteration — an older failed iteration is ignored when the latest is completed", () => {
    const run = makeRun({
      graphSnapshot: makeSnapshot(["a"]),
      nodeExecutions: [
        makeExecution({ templateNodeId: "a", status: "failed", iteration: 1 }),
        makeExecution({ templateNodeId: "a", status: "completed", iteration: 2 }),
      ],
    });

    expect(classifyActiveNodes(run).attention).toHaveLength(0);
    expect(pickFocusNode(run)).toBeNull();
  });

  it("includes the routing hub (Supervisor) when it needs attention", () => {
    const run = makeRun({
      graphSnapshot: makeSnapshot(["start", "supervisor"]),
      nodeExecutions: [
        makeExecution({ templateNodeId: "supervisor", status: "awaiting_human" }),
      ],
    });

    expect(pickFocusNode(run)).toBe("supervisor");
  });

  it("returns null for a completed-only run with no failures", () => {
    const run = makeRun({
      status: "completed",
      graphSnapshot: makeSnapshot(["a", "b"]),
      nodeExecutions: [
        makeExecution({ templateNodeId: "a", status: "completed" }),
        makeExecution({ templateNodeId: "b", status: "completed" }),
      ],
    });

    expect(pickFocusNode(run)).toBeNull();
  });

  it("returns null for a cancelled-only run", () => {
    const run = makeRun({
      status: "cancelled",
      graphSnapshot: makeSnapshot(["a"]),
      nodeExecutions: [makeExecution({ templateNodeId: "a", status: "cancelled" })],
    });

    expect(pickFocusNode(run)).toBeNull();
  });

  it("returns empty classes and null focus for a null snapshot", () => {
    const run = makeRun({ graphSnapshot: null });
    expect(classifyActiveNodes(run)).toEqual({ attention: [], running: [] });
    expect(pickFocusNode(run)).toBeNull();
  });

  it("falls back to the first running node when nothing needs attention", () => {
    const run = makeRun({
      graphSnapshot: makeSnapshot(["a", "b"]),
      nodeExecutions: [
        makeExecution({ templateNodeId: "a", status: "pending" }),
        makeExecution({ templateNodeId: "b", status: "running" }),
      ],
    });

    expect(pickFocusNode(run)).toBe("b");
  });
});
