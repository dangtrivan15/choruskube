import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentType, CSSProperties } from "react";
import { renderWithProviders } from "@/__tests__/test-utils";
import type { ExternalBlockerRef, RoadmapGraphSnapshot, TaskResponse } from "@/lib/types";

// Mock @xyflow/react — real ReactFlow attaches d3-zoom/d3-drag pointer
// handlers to the pane that crash happy-dom on pointerdown (no window on
// some internal node during nodrag's mousedowned handler). There is no
// existing RTL test for the (structurally identical) RunDag component either,
// for the same reason — DagNode.test.tsx mocks @xyflow/react's Handle for the
// same reason at the node level. This mock renders our own node/edge
// components directly (unmocked) inside plain divs, so RoadmapGraph's own
// filtering/click/collapse logic and RoadmapGraphNode/Edge/DependencyEdge's
// own rendering all still execute for real — only ReactFlow's pan/zoom shell
// is replaced.
vi.mock("@xyflow/react", () => {
  return {
    ReactFlow: ({
      nodes,
      edges,
      nodeTypes,
      edgeTypes,
      onNodeClick,
      onPaneClick,
    }: {
      nodes: { id: string; type: string; data: unknown }[];
      edges: {
        id: string;
        type: string;
        data: unknown;
        markerEnd?: unknown;
        source: string;
        target: string;
        sourceHandle?: string;
        targetHandle?: string;
      }[];
      nodeTypes: Record<string, ComponentType<{ id: string; data: unknown; selected: boolean }>>;
      edgeTypes: Record<
        string,
        ComponentType<{
          id: string;
          data: unknown;
          markerEnd?: unknown;
          sourceX: number;
          sourceY: number;
          targetX: number;
          targetY: number;
        }>
      >;
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
              onClick={(e) => {
                e.stopPropagation();
                onNodeClick?.(e, n);
              }}
            >
              <Comp id={n.id} data={n.data} selected={false} />
            </div>
          );
        })}
        {edges.map((e) => {
          const Comp = edgeTypes[e.type];
          return (
            <div
              key={e.id}
              data-testid={`mock-edge-${e.id}`}
              data-source={e.source}
              data-target={e.target}
              data-source-handle={e.sourceHandle}
              data-target-handle={e.targetHandle}
            >
              <Comp
                id={e.id}
                data={e.data}
                markerEnd={e.markerEnd}
                sourceX={0}
                sourceY={0}
                targetX={100}
                targetY={100}
              />
            </div>
          );
        })}
      </div>
    ),
    Controls: () => null,
    Background: () => null,
    MarkerType: { ArrowClosed: "arrowclosed" },
    BaseEdge: ({
      id,
      path,
      style,
      markerEnd,
    }: {
      id?: string;
      path: string;
      style?: CSSProperties;
      markerEnd?: unknown;
    }) => (
      <path
        data-testid="mock-base-edge"
        id={id}
        d={path}
        stroke={style?.stroke as string | undefined}
        strokeDasharray={style?.strokeDasharray as string | undefined}
        markerEnd={markerEnd ? "url(#marker)" : undefined}
      />
    ),
    Handle: ({ type, id }: { type: string; id?: string }) => (
      <div data-testid={id ? `handle-${id}` : `handle-${type}`} />
    ),
    Position: { Top: "top", Bottom: "bottom", Left: "left", Right: "right" },
  };
});

import RoadmapGraph from "@/components/roadmap/RoadmapGraph";

function makeSnapshot(overrides: Partial<RoadmapGraphSnapshot> = {}): RoadmapGraphSnapshot {
  return {
    epic: {
      id: "epic-1",
      title: "Add dark mode",
      description: "desc",
      motivation: null,
      stage: "in_progress",
      priority: "medium",
      targetDate: null,
      progress: { totalTasks: 2, doneTasks: 0, startedTasks: 0 },
      softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
      repos: [],
      createdAt: "2026-04-01T00:00:00Z",
      updatedAt: "2026-04-01T00:00:00Z",
      readyItemCount: 0,
      milestone: null,
    },
    stories: [
      {
        id: "story-1",
        epicId: "epic-1",
        title: "Dark theme toggle",
        description: "desc",
        stage: "backlog",
        priority: "medium",
        targetDate: null,
        readiness: "READY",
        readyTaskCount: 1,
        progress: { totalTasks: 2, doneTasks: 0, startedTasks: 0 },
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      },
      {
        id: "story-2",
        epicId: "epic-1",
        title: "Persist preference",
        description: "desc",
        stage: "backlog",
        priority: "medium",
        targetDate: null,
        readiness: "READY",
        readyTaskCount: 1,
        progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      },
    ],
    tasks: [
      {
        id: "task-1",
        storyId: "story-1",
        title: "Build toggle component",
        description: "desc",
        status: "in_progress",
        softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
        repos: [],
        latestRunId: null,
        latestRunStatus: null,
        readiness: "READY",
        recentRuns: [],
        totalRunCount: 0,
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      },
      {
        id: "task-2",
        storyId: "story-1",
        title: "Wire theme context",
        description: "desc",
        status: "backlog",
        softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
        repos: [],
        latestRunId: null,
        latestRunStatus: null,
        readiness: "READY",
        recentRuns: [],
        totalRunCount: 0,
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
      },
    ],
    dependencies: [],
    externalBlockers: [],
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

async function waitForGraphReady() {
  await waitFor(() =>
    expect(screen.getByTestId("roadmap-graph-container")).toHaveAttribute("data-elk-ready", "true"),
  );
}

describe("RoadmapGraph", () => {
  it("renders the Epic root plus its Story and Task descendants from a fixture snapshot", async () => {
    renderWithProviders(<RoadmapGraph snapshot={makeSnapshot()} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    expect(screen.getByText("Add dark mode")).toBeInTheDocument();
    expect(screen.getByText("Dark theme toggle")).toBeInTheDocument();
    expect(screen.getByText("Persist preference")).toBeInTheDocument();
    expect(screen.getByText("Build toggle component")).toBeInTheDocument();
    expect(screen.getByText("Wire theme context")).toBeInTheDocument();

    const nodes = screen.getAllByTestId("roadmap-graph-node");
    expect(nodes).toHaveLength(5); // 1 epic + 2 stories + 2 tasks
  });

  it("renders a blocked badge on a Task node whose readiness is BLOCKED", async () => {
    const snapshot = makeSnapshot({
      tasks: [
        {
          id: "task-1",
          storyId: "story-1",
          title: "Build toggle component",
          description: "desc",
          status: "backlog",
          softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
          repos: [],
          latestRunId: null,
          latestRunStatus: null,
          readiness: "BLOCKED",
          recentRuns: [],
          totalRunCount: 0,
          createdAt: "2026-04-01T00:00:00Z",
          updatedAt: "2026-04-01T00:00:00Z",
        },
      ],
    });
    renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    expect(screen.getByTestId("roadmap-graph-node-blocked-badge")).toBeInTheDocument();
  });

  it("does not render a blocked badge on a Task node whose readiness is READY", async () => {
    renderWithProviders(<RoadmapGraph snapshot={makeSnapshot()} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    expect(screen.queryByTestId("roadmap-graph-node-blocked-badge")).not.toBeInTheDocument();
  });

  it("renders a dependency edge visually distinct from a hierarchy edge", async () => {
    const snapshot = makeSnapshot({
      dependencies: [
        {
          id: "dep-1",
          blockingItemType: "task",
          blockingItemId: "task-1",
          blockedItemType: "task",
          blockedItemId: "task-2",
          createdAt: "2026-04-01T00:00:00Z",
        },
      ],
    });
    renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    const hierarchyEdge = screen.getByTestId("mock-edge-epic-1=>story-1").querySelector("path");
    const dependencyEdge = screen.getByTestId("mock-edge-dep:dep-1").querySelector("path");

    expect(hierarchyEdge).toBeTruthy();
    expect(dependencyEdge).toBeTruthy();
    // Dependency edges are dashed + carry an arrowhead marker; hierarchy edges are neither.
    expect(dependencyEdge?.getAttribute("stroke-dasharray")).toBeTruthy();
    expect(hierarchyEdge?.getAttribute("stroke-dasharray")).toBeFalsy();
    expect(dependencyEdge?.getAttribute("marker-end")).toBeTruthy();
    expect(hierarchyEdge?.getAttribute("marker-end")).toBeFalsy();
    // Distinct colors too.
    expect(dependencyEdge?.getAttribute("stroke")).not.toBe(hierarchyEdge?.getAttribute("stroke"));
  });

  it("renders an Epic-tier dependency edge (touching the Epic itself) visually distinct from a within-Epic dependency edge", async () => {
    const snapshot = makeSnapshot({
      dependencies: [
        {
          id: "dep-1",
          blockingItemType: "task",
          blockingItemId: "task-1",
          blockedItemType: "task",
          blockedItemId: "task-2",
          createdAt: "2026-04-01T00:00:00Z",
        },
        {
          // The Epic itself is the blocked side — a Story/Task can't proceed
          // to affect the Epic until "task-1" is done (Epic-tier dependency,
          // see EpicReadinessAssembler.loadEpicCandidates adding the Epic's
          // own id to its candidate set).
          id: "dep-epic-1",
          blockingItemType: "task",
          blockingItemId: "task-1",
          blockedItemType: "epic",
          blockedItemId: "epic-1",
          createdAt: "2026-04-01T00:00:00Z",
        },
      ],
    });
    renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    const dependencyEdge = screen.getByTestId("mock-edge-dep:dep-1").querySelector("path");
    const epicDependencyEdge = screen.getByTestId("mock-edge-dep:dep-epic-1").querySelector("path");

    expect(epicDependencyEdge).toBeTruthy();
    // Epic-tier edges are dashed + carry an arrowhead, same as an ordinary
    // dependency edge, but with a distinct dash pattern and color.
    expect(epicDependencyEdge?.getAttribute("stroke-dasharray")).toBeTruthy();
    expect(epicDependencyEdge?.getAttribute("marker-end")).toBeTruthy();
    expect(epicDependencyEdge?.getAttribute("stroke-dasharray")).not.toBe(
      dependencyEdge?.getAttribute("stroke-dasharray"),
    );
    expect(epicDependencyEdge?.getAttribute("stroke")).not.toBe(dependencyEdge?.getAttribute("stroke"));
  });

  it("renders a cross-Epic dependency edge visually distinct from a within-Epic dependency edge", async () => {
    const externalBlocker: ExternalBlockerRef = {
      itemType: "task",
      itemId: "ext-task-1",
      title: "External Blocking Task",
      epicId: "epic-2",
      epicTitle: "Other Epic",
      direction: "BLOCKING",
      internalItemId: "task-1",
    };
    const snapshot = makeSnapshot({
      dependencies: [
        {
          id: "dep-1",
          blockingItemType: "task",
          blockingItemId: "task-1",
          blockedItemType: "task",
          blockedItemId: "task-2",
          createdAt: "2026-04-01T00:00:00Z",
        },
      ],
      externalBlockers: [externalBlocker],
    });
    renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    const hierarchyEdge = screen.getByTestId("mock-edge-epic-1=>story-1").querySelector("path");
    const dependencyEdge = screen.getByTestId("mock-edge-dep:dep-1").querySelector("path");
    const crossEpicEdge = screen
      .getByTestId("mock-edge-cross-epic:ext-task-1:task-1")
      .querySelector("path");

    expect(hierarchyEdge).toBeTruthy();
    expect(dependencyEdge).toBeTruthy();
    expect(crossEpicEdge).toBeTruthy();

    // Cross-Epic edges are dashed/dotted + carry an arrowhead, same as
    // within-Epic dependency edges, but with a distinct dash pattern and color.
    expect(crossEpicEdge?.getAttribute("stroke-dasharray")).toBeTruthy();
    expect(crossEpicEdge?.getAttribute("marker-end")).toBeTruthy();
    expect(crossEpicEdge?.getAttribute("stroke-dasharray")).not.toBe(
      dependencyEdge?.getAttribute("stroke-dasharray"),
    );
    expect(crossEpicEdge?.getAttribute("stroke")).not.toBe(dependencyEdge?.getAttribute("stroke"));
    expect(crossEpicEdge?.getAttribute("stroke")).not.toBe(hierarchyEdge?.getAttribute("stroke"));
    expect(crossEpicEdge?.getAttribute("stroke-dasharray")).not.toBe(
      hierarchyEdge?.getAttribute("stroke-dasharray"),
    );
  });

  it("dedupes external blockers referencing the same item into one external node", async () => {
    const sharedExternalBlocker = {
      itemType: "task" as const,
      itemId: "ext-task-1",
      title: "External Blocking Task",
      epicId: "epic-2",
      epicTitle: "Other Epic",
      direction: "BLOCKING" as const,
    };
    const snapshot = makeSnapshot({
      externalBlockers: [
        { ...sharedExternalBlocker, internalItemId: "task-1" },
        { ...sharedExternalBlocker, internalItemId: "task-2" },
      ],
    });
    renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    expect(screen.getAllByTestId("mock-node-external:ext-task-1")).toHaveLength(1);
    expect(screen.getByTestId("mock-edge-cross-epic:ext-task-1:task-1")).toBeInTheDocument();
    expect(screen.getByTestId("mock-edge-cross-epic:ext-task-1:task-2")).toBeInTheDocument();
  });

  it.each([
    {
      direction: "BLOCKING" as const,
      description: "external item blocks the in-Epic item — edge runs external -> internal",
      expectedSource: "external:ext-task-1",
      expectedTarget: "task-1",
      expectedSourceHandle: "source-top",
      expectedTargetHandle: "target-bottom",
    },
    {
      direction: "BLOCKED" as const,
      description: "in-Epic item blocks the external item — edge runs internal -> external",
      expectedSource: "task-1",
      expectedTarget: "external:ext-task-1",
      expectedSourceHandle: "source-bottom",
      expectedTargetHandle: "target-top",
    },
  ])(
    "wires the cross-Epic edge's source/target/handles for direction=$direction ($description)",
    async ({ direction, expectedSource, expectedTarget, expectedSourceHandle, expectedTargetHandle }) => {
      const externalBlocker: ExternalBlockerRef = {
        itemType: "task",
        itemId: "ext-task-1",
        title: "External Task",
        epicId: "epic-2",
        epicTitle: "Other Epic",
        direction,
        internalItemId: "task-1",
      };
      const snapshot = makeSnapshot({ externalBlockers: [externalBlocker] });
      renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={vi.fn()} />);
      await waitForGraphReady();

      const edge = screen.getByTestId("mock-edge-cross-epic:ext-task-1:task-1");
      expect(edge).toHaveAttribute("data-source", expectedSource);
      expect(edge).toHaveAttribute("data-target", expectedTarget);
      expect(edge).toHaveAttribute("data-source-handle", expectedSourceHandle);
      expect(edge).toHaveAttribute("data-target-handle", expectedTargetHandle);
    },
  );

  it("clicking an external node navigates to the owning Epic's graph route", async () => {
    const externalBlocker: ExternalBlockerRef = {
      itemType: "task",
      itemId: "ext-task-1",
      title: "External Blocking Task",
      epicId: "epic-2",
      epicTitle: "Other Epic",
      direction: "BLOCKING",
      internalItemId: "task-1",
    };
    const snapshot = makeSnapshot({ externalBlockers: [externalBlocker] });
    const onNodeSelect = vi.fn();
    renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={onNodeSelect} />);
    await waitForGraphReady();

    const link = screen.getByTestId("roadmap-external-node");
    expect(link.tagName).toBe("A");
    expect(link).toHaveAttribute("href", "/roadmap/epics/epic-2/graph");

    // MemoryRouter has no <Routes> configured in this test's provider tree
    // (mirrors RoadmapGraphDetailPanel.test.tsx's equivalent sidebar-link
    // test), so the href itself is the navigation target the click resolves
    // to — clicking must not throw and must not trigger the internal
    // onNodeSelect flow (RoadmapExternalNode stops click propagation).
    const user = userEvent.setup();
    await user.click(link);
    expect(onNodeSelect).not.toHaveBeenCalled();
  });

  it("collapsing a Story hides its Task nodes, and expanding restores them", async () => {
    renderWithProviders(<RoadmapGraph snapshot={makeSnapshot()} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    expect(screen.getByText("Build toggle component")).toBeInTheDocument();
    expect(screen.getByText("Wire theme context")).toBeInTheDocument();

    const user = userEvent.setup();
    const collapseButton = screen
      .getByTestId("mock-node-story-1")
      .querySelector('[data-testid="roadmap-graph-node-toggle-collapse"]');
    expect(collapseButton).toBeTruthy();

    await user.click(collapseButton!);
    await waitForGraphReady();

    expect(screen.queryByText("Build toggle component")).not.toBeInTheDocument();
    expect(screen.queryByText("Wire theme context")).not.toBeInTheDocument();
    // Unrelated Story is untouched.
    expect(screen.getByText("Persist preference")).toBeInTheDocument();

    await user.click(
      screen
        .getByTestId("mock-node-story-1")
        .querySelector('[data-testid="roadmap-graph-node-toggle-collapse"]')!,
    );
    await waitForGraphReady();

    expect(screen.getByText("Build toggle component")).toBeInTheDocument();
    expect(screen.getByText("Wire theme context")).toBeInTheDocument();
  });

  it("calls onNodeSelect with the clicked item's detail on node click, and null on pane click", async () => {
    const onNodeSelect = vi.fn();
    renderWithProviders(<RoadmapGraph snapshot={makeSnapshot()} onNodeSelect={onNodeSelect} />);
    await waitForGraphReady();

    const user = userEvent.setup();
    await user.click(screen.getByTestId("mock-node-task-1"));

    expect(onNodeSelect).toHaveBeenCalledWith(
      expect.objectContaining({ itemType: "task", item: expect.objectContaining({ id: "task-1" }) }),
    );

    await user.click(screen.getByTestId("mock-react-flow-pane"));
    expect(onNodeSelect).toHaveBeenLastCalledWith(null);
  });

  it("auto-collapses a Story branch above the Task-count threshold on first render", async () => {
    const manyTasks: TaskResponse[] = Array.from({ length: 10 }, (_, i) => ({
      id: `task-big-${i}`,
      storyId: "story-1",
      title: `Big task ${i}`,
      description: "desc",
      status: "backlog",
      softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
      repos: [],
      latestRunId: null,
      latestRunStatus: null,
      readiness: "READY",
      recentRuns: [],
      totalRunCount: 0,
      createdAt: "2026-04-01T00:00:00Z",
      updatedAt: "2026-04-01T00:00:00Z",
    }));
    const snapshot = makeSnapshot({ tasks: manyTasks });

    renderWithProviders(<RoadmapGraph snapshot={snapshot} onNodeSelect={vi.fn()} />);
    await waitForGraphReady();

    // Story-1 has more Tasks than the auto-collapse threshold, so its Tasks
    // start hidden — only the Story node itself (marked collapsed) is visible.
    expect(screen.queryByText("Big task 0")).not.toBeInTheDocument();
    expect(screen.getByTestId("mock-node-story-1").querySelector('[data-collapsed="true"]')).toBeTruthy();
  });
});
