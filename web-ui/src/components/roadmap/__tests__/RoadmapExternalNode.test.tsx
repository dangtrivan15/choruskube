import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import type { NodeProps } from "@xyflow/react";
import RoadmapExternalNode, {
  type RoadmapExternalNodeData,
  type RoadmapExternalNodeType,
} from "@/components/roadmap/RoadmapExternalNode";

// Mirrors DagNode.test.tsx's @xyflow/react mock — only Handle/Position are
// needed to render this node in isolation (no ReactFlow pane/zoom shell).
vi.mock("@xyflow/react", () => ({
  Handle: ({ type, id }: { type: string; id?: string }) => (
    <div data-testid={id ? `handle-${id}` : `handle-${type}`} />
  ),
  Position: {
    Top: "top",
    Bottom: "bottom",
    Left: "left",
    Right: "right",
  },
}));

function renderNode(data: RoadmapExternalNodeData) {
  return renderWithProviders(
    <RoadmapExternalNode
      data={data}
      selected={false}
      id="external:other-task-1"
      type="roadmap-external"
      // Minimal required NodeProps fields RoadmapExternalNode doesn't read.
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
        width: 160,
        height: 48,
      } as unknown as Omit<NodeProps<RoadmapExternalNodeType>, "data" | "selected" | "id" | "type">)}
    />,
  );
}

describe("RoadmapExternalNode", () => {
  it("renders the blocker title and a link to its owning Epic's graph page", () => {
    renderNode({
      title: "Migrate auth service",
      epicId: "other-epic-1",
      epicTitle: "Auth Overhaul",
    });

    const link = screen.getByTestId("roadmap-external-node");
    expect(link.tagName).toBe("A");
    expect(link).toHaveTextContent("Migrate auth service");
    expect(link).toHaveTextContent("Auth Overhaul");
    expect(link).toHaveAttribute("href", "/roadmap/epics/other-epic-1/graph");
  });
});
