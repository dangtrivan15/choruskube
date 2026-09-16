import { describe, it, expect, vi, beforeEach } from "vitest";
import { act } from "react";
import { screen, waitFor } from "@testing-library/react";
import type { ComponentType, CSSProperties } from "react";
import { renderWithProviders } from "@/__tests__/test-utils";
import { candidateDocToGraph } from "@/lib/roadmapCandidateGraph";
import type { RoadmapCandidatesDocument } from "@/lib/types";

// Capture the flow's connect/delete handlers so the test can drive them
// directly — real ReactFlow's pointer-driven connect/select doesn't run under
// happy-dom (see RoadmapGraph.test.tsx for the same mocking rationale).
const flow = vi.hoisted(() => ({
  onConnect: undefined as ((c: { source: string; target: string }) => void) | undefined,
  onEdgesDelete: undefined as ((e: { id: string }[]) => void) | undefined,
}));

vi.mock("@xyflow/react", () => ({
  ReactFlow: ({
    nodes,
    edges,
    nodeTypes,
    edgeTypes,
    onConnect,
    onEdgesDelete,
  }: {
    nodes: { id: string; type: string; data: unknown }[];
    edges: { id: string; type: string; data: unknown; markerEnd?: unknown; source: string; target: string }[];
    nodeTypes: Record<string, ComponentType<{ id: string; data: unknown; selected: boolean }>>;
    edgeTypes: Record<
      string,
      ComponentType<{ id: string; data: unknown; markerEnd?: unknown; sourceX: number; sourceY: number; targetX: number; targetY: number }>
    >;
    onConnect?: (c: { source: string; target: string }) => void;
    onEdgesDelete?: (e: { id: string }[]) => void;
  }) => {
    flow.onConnect = onConnect;
    flow.onEdgesDelete = onEdgesDelete;
    return (
      <div data-testid="mock-flow">
        {nodes.map((n) => {
          const Comp = nodeTypes[n.type];
          return (
            <div key={n.id} data-testid={`mock-node-${n.id}`}>
              <Comp id={n.id} data={n.data} selected={false} />
            </div>
          );
        })}
        {edges.map((e) => {
          const Comp = edgeTypes[e.type];
          return (
            <div key={e.id} data-testid={`mock-edge-${e.id}`} data-source={e.source} data-target={e.target}>
              <Comp id={e.id} data={e.data} markerEnd={e.markerEnd} sourceX={0} sourceY={0} targetX={100} targetY={100} />
            </div>
          );
        })}
      </div>
    );
  },
  Controls: () => null,
  Background: () => null,
  MarkerType: { ArrowClosed: "arrowclosed" },
  BaseEdge: ({ id, path, style, markerEnd }: { id?: string; path: string; style?: CSSProperties; markerEnd?: unknown }) => (
    <path
      data-testid="mock-base-edge"
      id={id}
      d={path}
      stroke={style?.stroke as string | undefined}
      strokeDasharray={style?.strokeDasharray as string | undefined}
      markerEnd={markerEnd ? "url(#marker)" : undefined}
    />
  ),
  Handle: ({ type, id }: { type: string; id?: string }) => <div data-testid={id ? `handle-${id}` : `handle-${type}`} />,
  Position: { Top: "top", Bottom: "bottom", Left: "left", Right: "right" },
}));

import RoadmapCandidateGraph from "@/components/runs/RoadmapCandidateGraph";

function makeDoc(): RoadmapCandidatesDocument {
  return {
    milestones: [],
    epics: [
      {
        title: "Epic A",
        description: "",
        motivation: "",
        repos: null,
        priority: null,
        key: "epA",
        stories: [{ title: "Story A1", description: "", key: "stA1", tasks: [] }],
      },
      { title: "Epic B", description: "", motivation: "", repos: null, priority: null, key: "epB", stories: [] },
    ],
    dependencies: [{ blocking: "stA1", blocked: "epB" }],
  };
}

async function waitForElkReady() {
  await waitFor(() =>
    expect(screen.getByTestId("roadmap-candidate-graph")).toHaveAttribute("data-elk-ready", "true"),
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  flow.onConnect = undefined;
  flow.onEdgesDelete = undefined;
});

describe("RoadmapCandidateGraph", () => {
  it("renders a node per candidate Epic/Story and the proposed dependency edge", async () => {
    renderWithProviders(<RoadmapCandidateGraph value={makeDoc()} onChange={vi.fn()} />);
    await waitForElkReady();

    expect(screen.getByText("Epic A")).toBeInTheDocument();
    expect(screen.getByText("Story A1")).toBeInTheDocument();
    expect(screen.getByText("Epic B")).toBeInTheDocument();
    expect(screen.getByTestId("mock-edge-dep:0")).toBeInTheDocument();
  });

  it("adds a dependency when two nodes are connected on the canvas", async () => {
    const onChange = vi.fn();
    const doc = makeDoc();
    renderWithProviders(<RoadmapCandidateGraph value={doc} onChange={onChange} />);
    await waitForElkReady();

    const model = candidateDocToGraph(doc);
    const epA = model.nodes.find((n) => n.key === "epA")!.id;
    const stA1 = model.nodes.find((n) => n.key === "stA1")!.id;
    act(() => flow.onConnect!({ source: epA, target: stA1 }));

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        dependencies: expect.arrayContaining([
          { blocking: "stA1", blocked: "epB" },
          { blocking: "epA", blocked: "stA1" },
        ]),
      }),
    );
  });

  it("does not add a dependency that would create a cycle", async () => {
    const onChange = vi.fn();
    const doc = makeDoc(); // stA1 → epB already exists
    renderWithProviders(<RoadmapCandidateGraph value={doc} onChange={onChange} />);
    await waitForElkReady();

    const model = candidateDocToGraph(doc);
    const epB = model.nodes.find((n) => n.key === "epB")!.id;
    const stA1 = model.nodes.find((n) => n.key === "stA1")!.id;
    act(() => flow.onConnect!({ source: epB, target: stA1 })); // would close stA1→epB→stA1

    expect(onChange).not.toHaveBeenCalled();
  });

  it("removes a dependency when its edge is deleted on the canvas", async () => {
    const onChange = vi.fn();
    const doc = makeDoc();
    renderWithProviders(<RoadmapCandidateGraph value={doc} onChange={onChange} />);
    await waitForElkReady();

    act(() => flow.onEdgesDelete!([{ id: "dep:0" }]));

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ dependencies: [] }));
  });
});
