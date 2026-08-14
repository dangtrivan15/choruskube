import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import type { ComponentType, ReactNode } from "react";
import { renderWithProviders } from "@/__tests__/test-utils";
import type { GraphSnapshot, NodeExecutionResponse, RunResponse } from "@/lib/types";

// Mock @xyflow/react — real ReactFlow attaches d3-zoom/d3-drag pointer handlers to the pane
// that crash happy-dom on pointerdown (no window on some internal node during nodrag's
// mousedowned handler); see RoadmapGraph.test.tsx's identical rationale, which this mock
// mirrors for RunDag's structurally-similar ELK-driven canvas.
//
// The edge wrapper below reproduces two facts read directly out of the installed
// @xyflow/react source (node_modules/@xyflow/react/dist/esm/index.js), rather than assumed:
//   - the rendered <g> wrapper's className is `cc(['react-flow__edge', ..., edge.className, ...])`
//     — i.e. a caller-supplied `edge.className` DOES reach the DOM, unmangled.
//   - that same wrapper carries `data-id: id` and `data-testid: rf__edge-${id}`.
// Nodes are delegated to the real (unmocked) node components, same as RoadmapGraph.test.tsx,
// so DagNode's own rendering — label formatting, status text, routing-hub styling — executes
// for real. Edges render as plain attribute-carrying wrappers rather than delegating to
// `edgeTypes` — this test only asserts on the edge list React Flow receives (id/type/class),
// never DagEdge's own path-rendering internals, so DagEdge is never invoked here.
vi.mock("@xyflow/react", () => {
  return {
    ReactFlow: ({
      nodes,
      edges,
      nodeTypes,
      onNodeClick,
      onPaneClick,
    }: {
      nodes: { id: string; type: string; data: unknown; position: { x: number; y: number } }[];
      edges: {
        id: string;
        type?: string;
        source: string;
        target: string;
        label?: ReactNode;
        className?: string;
      }[];
      nodeTypes: Record<string, ComponentType<{ id: string; data: unknown; selected: boolean }>>;
      onNodeClick?: (event: unknown, node: { id: string }) => void;
      onPaneClick?: () => void;
    }) => (
      <div data-testid="mock-react-flow-pane" onClick={() => onPaneClick?.()}>
        {nodes.map((n) => {
          const Comp = nodeTypes[n.type];
          return (
            <div
              key={n.id}
              data-testid={`mock-node-${n.id}`}
              data-x={n.position.x}
              data-y={n.position.y}
              onClick={(e) => {
                e.stopPropagation();
                onNodeClick?.(e, n);
              }}
            >
              <Comp id={n.id} data={n.data} selected={false} />
            </div>
          );
        })}
        {edges.map((e) => (
          <g
            key={e.id}
            data-testid={`rf__edge-${e.id}`}
            data-id={e.id}
            data-source={e.source}
            data-target={e.target}
            data-type={e.type ?? "default"}
            data-label={typeof e.label === "string" ? e.label : undefined}
            className={["react-flow__edge", e.className].filter(Boolean).join(" ")}
          />
        ))}
      </div>
    ),
    Controls: () => null,
    Background: () => null,
    MarkerType: { ArrowClosed: "arrowclosed" },
    Handle: ({ type, id }: { type: string; id?: string }) => (
      <div data-testid={id ? `handle-${id}` : `handle-${type}`} />
    ),
    Position: { Top: "top", Bottom: "bottom", Left: "left", Right: "right" },
  };
});

// Wraps @/lib/elkLayout's real `computeElkLayout` so tests can inspect exactly which snapshot
// each call received (requirement: all three layout consumers — computeElkLayout,
// buildFallbackLayout, and buildTopologyKey — must see a Supervisor-free snapshot) while still
// running the genuine elkjs layout underneath, same as RoadmapGraph.test.tsx does for its own
// (unmocked) ELK-backed layout.
const computeElkLayoutCalls: GraphSnapshot[] = [];
vi.mock("@/lib/elkLayout", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/elkLayout")>();
  return {
    ...actual,
    computeElkLayout: vi.fn((snapshot: GraphSnapshot) => {
      computeElkLayoutCalls.push(snapshot);
      return actual.computeElkLayout(snapshot);
    }),
  };
});

import RunDag from "../RunDag";
import { computeElkLayout } from "@/lib/elkLayout";

const START_ID = "start";
const CODE_REVIEW_ID = "code_review";
const FINAL_APPROVAL_ID = "final_approval";
const SUPERVISOR_ID = "supervisor";

function snapshotWithSupervisor(): GraphSnapshot {
  return {
    nodes: [
      { template_node_id: START_ID, label: "start", executor_type: "ai", is_entrypoint: true },
      { template_node_id: CODE_REVIEW_ID, label: "code_review", executor_type: "ai", is_entrypoint: false },
      {
        template_node_id: FINAL_APPROVAL_ID,
        label: "final_approval",
        executor_type: "human",
        is_entrypoint: false,
      },
      {
        template_node_id: SUPERVISOR_ID,
        label: "supervisor",
        executor_type: "human",
        is_entrypoint: false,
        config_overrides: { routing_hub: true },
      },
    ],
    edges: [
      { template_edge_id: "e1", source_node_id: START_ID, target_node_id: CODE_REVIEW_ID, condition: null },
      {
        template_edge_id: "e2",
        source_node_id: CODE_REVIEW_ID,
        target_node_id: FINAL_APPROVAL_ID,
        condition: "approved",
      },
    ],
  };
}

function snapshotWithoutSupervisor(): GraphSnapshot {
  return {
    nodes: [
      { template_node_id: START_ID, label: "start", executor_type: "ai", is_entrypoint: true },
      { template_node_id: CODE_REVIEW_ID, label: "code_review", executor_type: "ai", is_entrypoint: false },
    ],
    edges: [
      { template_edge_id: "e1", source_node_id: START_ID, target_node_id: CODE_REVIEW_ID, condition: null },
    ],
  };
}

const EXPECTED_REAL_EDGES = snapshotWithSupervisor().edges.length; // 2

function makeExecution(
  overrides: Partial<NodeExecutionResponse> & { templateNodeId: string },
): NodeExecutionResponse {
  return {
    id: `exec-${overrides.templateNodeId}`,
    status: "pending",
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

function makeRun(overrides: Partial<RunResponse> = {}): RunResponse {
  return {
    id: "run-1",
    graphTemplateId: "tpl-1",
    templateName: "Feature Dev",
    name: null,
    status: "running",
    externalRunId: "ext-1",
    graphVersion: 1,
    graphSnapshot: snapshotWithSupervisor(),
    startedAt: null,
    completedAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    nodeExecutions: [],
    pullRequests: [],
    promptText: null,
    task: null,
    softwareProject: null,
    ...overrides,
  };
}

function renderDag(
  snapshot: GraphSnapshot,
  options: { nodeExecutions?: Array<Partial<NodeExecutionResponse> & { templateNodeId: string }> } = {},
) {
  const nodeExecutions = (options.nodeExecutions ?? []).map(makeExecution);
  const run = makeRun({ graphSnapshot: snapshot, nodeExecutions });
  return renderWithProviders(<RunDag run={run} onNodeSelect={vi.fn()} />);
}

async function waitForGraphReady() {
  await waitFor(() =>
    expect(screen.getByTestId("run-dag-container")).toHaveAttribute("data-elk-ready", "true"),
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  computeElkLayoutCalls.length = 0;
});

describe("RunDag — Supervisor rendering", () => {
  it("renders a template without a Supervisor exactly as before: no hub node, no synthetic edges", async () => {
    const { container } = renderDag(snapshotWithoutSupervisor());
    await waitForGraphReady();

    expect(screen.getAllByTestId(/^mock-node-/)).toHaveLength(2);
    expect(container.querySelectorAll('[data-testid^="rf__edge-"]')).toHaveLength(1);
    expect(container.querySelectorAll(".supervisor-edge")).toHaveLength(0);
    expect(screen.queryByTestId("dag-node-routing-hub-caption")).not.toBeInTheDocument();
  });

  it("keeps the Supervisor out of the laid-out graph but still renders it, pending, before it has ever run", async () => {
    const { container } = renderDag(snapshotWithSupervisor());
    await waitForGraphReady();

    // The hub renders even though it has no execution row at all.
    expect(await screen.findByText("Supervisor")).toBeInTheDocument();
    const hubNode = screen.getByTestId(`mock-node-${SUPERVISOR_ID}`);
    expect(hubNode.querySelector('[data-testid="dag-node"]')).toHaveTextContent("pending");
    expect(hubNode.querySelector('[data-testid="dag-node"]')?.className).toMatch(/border-dashed/);

    // No real edge touches it — its connections are synthetic and only appear once an
    // escalation has actually happened (next test).
    expect(container.querySelectorAll('[data-testid^="rf__edge-"]')).toHaveLength(EXPECTED_REAL_EDGES);
    expect(container.querySelectorAll(".supervisor-edge")).toHaveLength(0);

    // Pinned beside the laid-out graph, not among its ELK-computed positions: strictly to the
    // right of every laid-out node.
    const otherXs = screen
      .getAllByTestId(/^mock-node-/)
      .filter((el) => el.dataset.testid !== `mock-node-${SUPERVISOR_ID}`)
      .map((el) => Number(el.dataset.x));
    expect(Number(hubNode.dataset.x)).toBeGreaterThan(Math.max(...otherXs));
  });

  it("draws dashed escalation edges once an escalation has happened", async () => {
    const { container } = renderDag(snapshotWithSupervisor(), {
      nodeExecutions: [
        { templateNodeId: CODE_REVIEW_ID, status: "completed", decision: "escalate" },
        { templateNodeId: SUPERVISOR_ID, status: "completed", decision: "route:final_approval" },
      ],
    });
    await waitForGraphReady();

    const synthetic = container.querySelectorAll(".supervisor-edge");
    expect(synthetic).toHaveLength(2);

    const ids = [...synthetic].map((el) => el.getAttribute("data-id"));
    expect(ids).toContain(`supervisor:${CODE_REVIEW_ID}->${SUPERVISOR_ID}`);
    expect(ids).toContain(`supervisor:${SUPERVISOR_ID}->${FINAL_APPROVAL_ID}`);

    // Neither synthetic edge uses the custom `dag` edge type (which would render an
    // ELK-computed route these edges by construction don't have).
    for (const el of synthetic) {
      expect(el.getAttribute("data-type")).toBe("default");
    }

    // Real graph edges are unaffected and still typed `dag`.
    const realEdges = [...container.querySelectorAll('[data-testid^="rf__edge-"]')].filter(
      (el) => !el.classList.contains("supervisor-edge"),
    );
    expect(realEdges).toHaveLength(EXPECTED_REAL_EDGES);
    for (const el of realEdges) {
      expect(el.getAttribute("data-type")).toBe("dag");
    }
  });

  it("selects the most recently completed escalation, not the first one in array order", async () => {
    const { container } = renderDag(snapshotWithSupervisor(), {
      nodeExecutions: [
        // Deliberately listed BEFORE the actual latest escalation, and with an earlier
        // completedAt, so a naive `.find()` over array order (`run.nodeExecutions` carries no
        // ordering guarantee) would wrongly pick this stale one.
        {
          templateNodeId: START_ID,
          status: "completed",
          decision: "escalate",
          completedAt: "2026-01-01T00:00:00Z",
        },
        {
          templateNodeId: CODE_REVIEW_ID,
          status: "completed",
          decision: "escalate",
          completedAt: "2026-01-01T01:00:00Z",
        },
      ],
    });
    await waitForGraphReady();

    const synthetic = container.querySelectorAll(".supervisor-edge");
    expect(synthetic).toHaveLength(1);
    expect(synthetic[0].getAttribute("data-id")).toBe(`supervisor:${CODE_REVIEW_ID}->${SUPERVISOR_ID}`);
  });

  it("does not draw a synthetic edge for a route:<label> decision naming an unknown node", async () => {
    const { container } = renderDag(snapshotWithSupervisor(), {
      nodeExecutions: [{ templateNodeId: SUPERVISOR_ID, status: "completed", decision: "route:nonexistent" }],
    });
    await waitForGraphReady();

    expect(container.querySelectorAll(".supervisor-edge")).toHaveLength(0);
  });

  it("feeds computeElkLayout a Supervisor-free snapshot", async () => {
    renderDag(snapshotWithSupervisor());
    await waitForGraphReady();

    expect(computeElkLayoutCalls.length).toBeGreaterThan(0);
    for (const snapshot of computeElkLayoutCalls) {
      expect(snapshot.nodes.some((n) => n.template_node_id === SUPERVISOR_ID)).toBe(false);
    }
  });

  it("falls back to a Supervisor-free layout (and still pins the hub) when ELK fails", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.mocked(computeElkLayout).mockImplementationOnce(() => Promise.reject(new Error("elk boom")));

    renderDag(snapshotWithSupervisor());

    await waitFor(() =>
      expect(screen.getByTestId("run-dag-container")).toHaveAttribute("data-elk-fallback", "true"),
    );

    const hubNode = screen.getByTestId(`mock-node-${SUPERVISOR_ID}`);
    const otherXs = screen
      .getAllByTestId(/^mock-node-/)
      .filter((el) => el.dataset.testid !== `mock-node-${SUPERVISOR_ID}`)
      .map((el) => Number(el.dataset.x));
    expect(Number(hubNode.dataset.x)).toBeGreaterThan(Math.max(...otherXs));

    consoleError.mockRestore();
  });
});
