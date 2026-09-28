import { describe, it, expect, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import type { NodeProps } from "@xyflow/react";
import RoadmapGraphNode from "../RoadmapGraphNode";
import type { RoadmapGraphNodeData, RoadmapGraphNodeType } from "../RoadmapGraphNode";

// Mock @xyflow/react's Handle the same way DagNode.test.tsx does — real
// Handle attaches pointer/zoom internals RunDag's own tests avoid for the
// same reason (see that file's mock for the full rationale).
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

function renderNode(data: Partial<RoadmapGraphNodeData> = {}, selected = false) {
  const defaultData: RoadmapGraphNodeData = {
    label: "Test Item",
    itemType: "task",
    status: "backlog",
    ...data,
  };

  return renderWithProviders(
    <RoadmapGraphNode
      id="node-1"
      data={defaultData}
      selected={selected}
      type="roadmap"
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
      } as unknown as Omit<NodeProps<RoadmapGraphNodeType>, "id" | "data" | "selected" | "type">)}
    />,
  );
}

describe("RoadmapGraphNode status coloring", () => {
  it("backlog maps to the neutral token on the icon, ink on the word", () => {
    renderNode({ status: "backlog" });
    const statusText = screen.getByText("backlog");
    expect(statusText.className).toContain("text-foreground");
    expect(statusText.className).not.toMatch(/text-status-/);
    expect(screen.getByTestId("roadmap-graph-node-status-icon").className).toMatch(/text-status-neutral/);
  });

  it("in_progress maps to the info token on the icon, ink on the word", () => {
    renderNode({ status: "in_progress" });
    const statusText = screen.getByText("in progress");
    expect(statusText.className).toContain("text-foreground");
    expect(screen.getByTestId("roadmap-graph-node-status-icon").className).toMatch(/text-status-info/);
  });

  it("done maps to the success token on the icon, ink on the word", () => {
    renderNode({ status: "done" });
    const statusText = screen.getByText("done");
    expect(statusText.className).toContain("text-foreground");
    expect(screen.getByTestId("roadmap-graph-node-status-icon").className).toMatch(/text-status-success/);
  });

  it("rolled_out (the container terminal stage) also maps to the success token on the icon", () => {
    renderNode({ status: "rolled_out" });
    const statusText = screen.getByText("rolled out");
    expect(statusText.className).toContain("text-foreground");
    expect(screen.getByTestId("roadmap-graph-node-status-icon").className).toMatch(/text-status-success/);
  });

  it("backlog, in_progress, and done/rolled_out resolve to pairwise-distinct icon tokens", () => {
    const statuses = ["backlog", "in_progress", "done", "rolled_out"];
    const tokens = statuses.map((status) => {
      const { container } = renderNode({ status, label: `item-${status}` });
      const text = within(container).getByText(status.replace(/_/g, " "));
      expect(text.className).toContain("text-foreground");
      const icon = within(container).getByTestId("roadmap-graph-node-status-icon");
      return icon.className.match(/text-status-\S+/)?.[0];
    });
    expect(tokens.every(Boolean)).toBe(true);
    // done and rolled_out share the success token by design (both are
    // terminal states); backlog/in_progress/{done,rolled_out} must still be
    // three distinct token families.
    expect(new Set(tokens).size).toBe(3);
  });

  it("a blocked node shows the warning-tinted blocked badge", () => {
    renderNode({ readiness: "BLOCKED" });
    const badge = screen.getByTestId("roadmap-graph-node-blocked-badge");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("bg-tint-status-warning");
    expect(badge.className).toContain("text-foreground");
  });

  it("a ready node does not show the blocked badge", () => {
    renderNode({ readiness: "READY" });
    expect(screen.queryByTestId("roadmap-graph-node-blocked-badge")).not.toBeInTheDocument();
  });
});
