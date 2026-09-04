import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import type { NodeProps } from "@xyflow/react";
import DagNode, { formatNodeLabel } from "../DagNode";
import type { DagNodeData, DagNodeType } from "../DagNode";

// Mock @xyflow/react Handle component
vi.mock("@xyflow/react", () => ({
  Handle: ({ type, position, id }: { type: string; position: string; id?: string }) => (
    <div data-testid={id ? `handle-${id}` : `handle-${type}`} data-type={type} data-position={position} />
  ),
  Position: {
    Top: "top",
    Bottom: "bottom",
    Left: "left",
    Right: "right",
  },
}));

function renderDagNode(data: Partial<DagNodeData> = {}, selected = false) {
  const defaultData: DagNodeData = {
    label: "Test Node",
    executorType: "ai",
    status: "pending",
    iteration: 1,
    ...data,
  };

  // DagNode is a memo'd component exported as default, the inner component accepts NodeProps
  // We render it with the expected props structure
  return renderWithProviders(
    <DagNode
      data={defaultData}
      selected={selected}
      id="node-1"
      type="dag"
      // Provide minimal required NodeProps fields
      {...({
        dragging: false,
        zIndex: 1,
        isConnectable: true,
        positionAbsoluteX: 0,
        positionAbsoluteY: 0,
        deletable: false,
        selectable: true,
        parentId: undefined,
        sourcePosition: undefined,
        targetPosition: undefined,
        dragHandle: undefined,
        width: 200,
        height: 80,
      } as unknown as Omit<NodeProps<DagNodeType>, "data" | "selected" | "id" | "type">)}
    />
  );
}

describe("DagNode", () => {
  it("renders the node label", () => {
    renderDagNode({ label: "My Node" });
    expect(screen.getByText("My Node")).toBeInTheDocument();
  });

  it("renders the status text with underscores replaced", () => {
    renderDagNode({ status: "awaiting_human" });
    expect(screen.getByText("awaiting human")).toBeInTheDocument();
  });

  it("renders iteration badge when iteration > 1", () => {
    renderDagNode({ iteration: 3 });
    expect(screen.getByText("iter 3")).toBeInTheDocument();
  });

  it("does not render iteration badge when iteration is 1", () => {
    renderDagNode({ iteration: 1 });
    expect(screen.queryByText(/iter/)).not.toBeInTheDocument();
  });

  it("renders all 8 directional handles (4 target + 4 source)", () => {
    renderDagNode();
    // Target handles
    expect(screen.getByTestId("handle-target-left")).toBeInTheDocument();
    expect(screen.getByTestId("handle-target-right")).toBeInTheDocument();
    expect(screen.getByTestId("handle-target-top")).toBeInTheDocument();
    expect(screen.getByTestId("handle-target-bottom")).toBeInTheDocument();
    // Source handles
    expect(screen.getByTestId("handle-source-right")).toBeInTheDocument();
    expect(screen.getByTestId("handle-source-left")).toBeInTheDocument();
    expect(screen.getByTestId("handle-source-top")).toBeInTheDocument();
    expect(screen.getByTestId("handle-source-bottom")).toBeInTheDocument();
  });

  it("assigns correct types to handles", () => {
    renderDagNode();
    expect(screen.getByTestId("handle-target-left").dataset.type).toBe("target");
    expect(screen.getByTestId("handle-source-right").dataset.type).toBe("source");
  });

  it("renders status for completed nodes", () => {
    renderDagNode({ status: "completed" });
    expect(screen.getByText("completed")).toBeInTheDocument();
  });

  it("renders status for failed nodes", () => {
    renderDagNode({ status: "failed" });
    expect(screen.getByText("failed")).toBeInTheDocument();
  });

  it("renders status for running nodes", () => {
    renderDagNode({ status: "running" });
    expect(screen.getByText("running")).toBeInTheDocument();
  });

  it("handles unknown status gracefully (falls back to pending styles)", () => {
    renderDagNode({ status: "unknown" });
    expect(screen.getByText("unknown")).toBeInTheDocument();
  });

  it("renders cancelled status", () => {
    renderDagNode({ status: "cancelled" });
    expect(screen.getByText("cancelled")).toBeInTheDocument();
  });

  it("renders awaiting_retry status", () => {
    renderDagNode({ status: "awaiting_retry" });
    expect(screen.getByText("awaiting retry")).toBeInTheDocument();
  });

  it("renders paused status with accent color and no pulse animation", () => {
    const { container } = renderDagNode({ status: "paused" });
    // Status text is rendered
    expect(screen.getByText("paused")).toBeInTheDocument();
    // Badge/status text should carry the accent color token (not error/neutral)
    // statusColors.ts returns "bg-status-accent/15 text-status-accent ..." for "paused"
    const statusText = screen.getByText("paused");
    expect(statusText.className).toMatch(/text-status-accent/);
    // Node border/background must NOT carry the running pulse animation
    expect(container.querySelector(".animate-pulse")).toBeNull();
  });

  it("title-cases snake_case labels for human gates", () => {
    renderDagNode({ label: "approve_spec_and_plan", executorType: "human" });
    expect(screen.getByText("Approve Spec And Plan")).toBeInTheDocument();
  });

  it("title-cases labels with no prefix", () => {
    renderDagNode({ label: "code_review", executorType: "ai" });
    expect(screen.getByText("Code Review")).toBeInTheDocument();
  });
});

describe("formatNodeLabel", () => {
  it("title-cases multi-word snake_case labels", () => {
    expect(formatNodeLabel("draft_spec_and_plan")).toBe("Draft Spec And Plan");
  });

  it("title-cases a single word", () => {
    expect(formatNodeLabel("implement")).toBe("Implement");
  });

  it("returns an empty string for an empty label", () => {
    expect(formatNodeLabel("")).toBe("");
  });
});
