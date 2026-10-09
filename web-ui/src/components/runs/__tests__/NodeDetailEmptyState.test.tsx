import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import NodeDetailEmptyState from "../NodeDetailEmptyState";
import type { GraphSnapshot, NodeExecutionResponse, RunResponse } from "@/lib/types";

function makeExecution(
  overrides: Partial<NodeExecutionResponse> & { templateNodeId: string; status: string },
): NodeExecutionResponse {
  return {
    id: `exec-${overrides.templateNodeId}`,
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

describe("NodeDetailEmptyState", () => {
  it("shows the no-snapshot message when there is no graph snapshot", () => {
    renderWithProviders(<NodeDetailEmptyState run={makeRun()} onSelectNode={vi.fn()} />);
    expect(screen.getByTestId("node-detail-empty")).toHaveTextContent("No graph snapshot.");
  });

  it("shows no sections when nothing needs attention or is running", () => {
    renderWithProviders(
      <NodeDetailEmptyState
        run={makeRun({ graphSnapshot: makeSnapshot(["a"]) })}
        onSelectNode={vi.fn()}
      />,
    );
    expect(screen.queryByText("Needs attention")).not.toBeInTheDocument();
    expect(screen.queryByText("Running")).not.toBeInTheDocument();
  });

  it("lists attention and running nodes, and clicking one calls onSelectNode with its id", async () => {
    const user = userEvent.setup();
    const onSelectNode = vi.fn();
    renderWithProviders(
      <NodeDetailEmptyState
        run={makeRun({
          graphSnapshot: makeSnapshot(["gate", "running_node"]),
          nodeExecutions: [
            makeExecution({ templateNodeId: "gate", status: "awaiting_human" }),
            makeExecution({ templateNodeId: "running_node", status: "running" }),
          ],
        })}
        onSelectNode={onSelectNode}
      />,
    );

    expect(screen.getByText("Needs attention")).toBeInTheDocument();
    expect(screen.getByText("Running")).toBeInTheDocument();
    const items = screen.getAllByTestId("node-detail-empty-item");
    expect(items).toHaveLength(2);

    await user.click(screen.getByText("Gate"));
    expect(onSelectNode).toHaveBeenCalledWith("gate");
  });
});
