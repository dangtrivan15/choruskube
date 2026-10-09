import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route } from "react-router";
import { ActivityFeedProvider } from "@/hooks/useActivityFeed";
import { createTestQueryClient } from "@/__tests__/test-utils";
import type { GraphSnapshot, NodeExecutionResponse, RunResponse } from "@/lib/types";

// ---------------------------------------------------------------------------
// Tier control — a single mock backs both `useMediaQuery` (read directly by
// the page for the docked-panel query) and `useMobileBreakpoint` (which
// delegates to the same module), so one knob drives phone/tablet/desktop.
// ---------------------------------------------------------------------------
let mockTier: "phone" | "tablet" | "desktop" = "desktop";
vi.mock("@/hooks/useMediaQuery", () => ({
  MOBILE_QUERY: "(max-width: 767px)",
  DOCKED_PANEL_QUERY: "(min-width: 1024px)",
  useMediaQuery: (query: string) => {
    if (query === "(max-width: 767px)") return mockTier === "phone";
    if (query === "(min-width: 1024px)") return mockTier === "desktop";
    return false;
  },
}));

// ---------------------------------------------------------------------------
// RunDag — a light stub exposing onNodeSelect and the props it received, so
// tests can simulate a node click and inspect viewportKey/focusNodeId/etc.
// without exercising ELK/React-Flow at all.
// ---------------------------------------------------------------------------
vi.mock("@/components/runs/RunDag", () => ({
  default: (props: {
    run: RunResponse;
    onNodeSelect: (id: string | null) => void;
    selectedNodeId?: string | null;
    focusNodeId?: string | null;
    compact?: boolean;
    viewportKey?: string;
  }) => (
    <div
      data-testid="rundag-stub"
      data-selected={props.selectedNodeId ?? ""}
      data-focus={props.focusNodeId ?? ""}
      data-compact={props.compact ? "true" : "false"}
      data-viewport-key={props.viewportKey ?? ""}
    >
      {props.run.graphSnapshot?.nodes.map((n) => (
        <button key={n.template_node_id} onClick={() => props.onNodeSelect(n.template_node_id)}>
          {n.label}
        </button>
      ))}
      <button onClick={() => props.onNodeSelect(null)}>deselect-pane</button>
    </div>
  ),
}));

// ---------------------------------------------------------------------------
// Network-hitting hooks DetailPanel/HumanGatePanel pull in — mocked the same
// way DetailPanel.test.tsx mocks them, so a selected gate node can render
// without a real backend.
// ---------------------------------------------------------------------------
const mockPauseMutate = vi.fn();
const mockResumeMutate = vi.fn();
const mockCancelMutate = vi.fn();
const mockRenameMutate = vi.fn();
const mockSignalMutate = vi.fn();

let mockRun: RunResponse | undefined;
let mockIsLoading = false;

vi.mock("@/hooks/useRuns", () => ({
  useRun: () => ({ data: mockRun, isLoading: mockIsLoading }),
  usePauseRun: () => ({ mutate: mockPauseMutate, isPending: false }),
  useResumeRun: () => ({ mutate: mockResumeMutate, isPending: false }),
  useCancelRun: () => ({ mutate: mockCancelMutate, isPending: false }),
  useRenameRun: () => ({ mutate: mockRenameMutate, isPending: false }),
  useSignalNode: () => ({ mutate: mockSignalMutate, isPending: false }),
  useRetryNode: () => ({ mutate: vi.fn(), isPending: false }),
  useReviewHistory: () => ({ data: [], isLoading: false }),
}));

vi.mock("@/hooks/useRunSubscription", () => ({
  useRunSubscription: () => {},
}));

vi.mock("@/hooks/useArtifacts", () => ({
  useArtifacts: () => ({ data: [], isLoading: false }),
  useArtifactContent: () => ({ data: undefined, isLoading: false }),
  useArtifactsForGroups: () => [],
}));

vi.mock("@/hooks/useExecutionLogs", () => ({
  useExecutionLogs: () => ({ data: [], isLoading: false }),
}));

import RunMonitorPage from "../RunMonitorPage";

function makeSnapshot(
  nodes: Array<{ id: string; label?: string; executorType?: "ai" | "human" }>,
): GraphSnapshot {
  return {
    nodes: nodes.map((n) => ({
      template_node_id: n.id,
      label: n.label ?? n.id,
      executor_type: n.executorType ?? "ai",
      is_entrypoint: false,
    })),
    edges: [],
  };
}

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

function makeRun(overrides: Partial<RunResponse> = {}): RunResponse {
  return {
    id: "run-1",
    graphTemplateId: "tpl-1",
    templateName: "Feature Dev",
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

function renderPage(initialPath = "/runs/run-1") {
  const queryClient = createTestQueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <ActivityFeedProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/runs/:id" element={<RunMonitorPage />} />
          </Routes>
        </MemoryRouter>
      </ActivityFeedProvider>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockTier = "desktop";
  mockRun = undefined;
  mockIsLoading = false;
});

describe("RunMonitorPage", () => {
  it("shows the loading skeleton while the run is loading", () => {
    mockIsLoading = true;
    renderPage();
    expect(screen.queryByTestId("run-not-found")).not.toBeInTheDocument();
    expect(document.querySelectorAll('[class*="animate-pulse"]').length).toBeGreaterThan(0);
  });

  it("shows run-not-found when the run doesn't exist", () => {
    mockRun = undefined;
    mockIsLoading = false;
    renderPage();
    expect(screen.getByTestId("run-not-found")).toBeInTheDocument();
  });

  describe("docked (desktop) tier", () => {
    it("renders the strip and the empty state side by side", () => {
      mockRun = makeRun({ promptText: "Add a thing", graphSnapshot: makeSnapshot([{ id: "a" }]) });
      renderPage();

      expect(screen.getByTestId("run-summary")).toBeInTheDocument();
      expect(screen.getByTestId("node-detail-empty")).toBeInTheDocument();
    });

    it("a valid ?node= selects that node and shows the detail panel", () => {
      mockRun = makeRun({
        graphSnapshot: makeSnapshot([{ id: "a", label: "step_one" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "a", status: "pending" })],
      });
      renderPage("/runs/run-1?node=a");

      expect(screen.getByTestId("detail-panel")).toBeInTheDocument();
      expect(screen.getByTestId("detail-node-label")).toHaveTextContent("step_one");
    });

    it("an unknown ?node= falls back to the empty state", () => {
      mockRun = makeRun({ graphSnapshot: makeSnapshot([{ id: "a" }]) });
      renderPage("/runs/run-1?node=does-not-exist");

      expect(screen.queryByTestId("detail-panel")).not.toBeInTheDocument();
      expect(screen.getByTestId("node-detail-empty")).toBeInTheDocument();
    });

    it("auto-focuses the awaiting-human node once, writing ?node= with replace, and not again after deselect", async () => {
      const user = userEvent.setup();
      mockRun = makeRun({
        graphSnapshot: makeSnapshot([
          { id: "gate", label: "review_gate", executorType: "human" },
          { id: "other", label: "other_step" },
        ]),
        nodeExecutions: [makeExecution({ templateNodeId: "gate", status: "awaiting_human" })],
      });
      renderPage("/runs/run-1");

      await waitFor(() => expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-selected", "gate"));

      // Deselecting (clicking the pane) must not bring auto-focus back.
      await user.click(screen.getByText("deselect-pane"));
      await waitFor(() => expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-selected", ""));
      expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-selected", "");
    });

    it("re-applies auto-focus for a different run id, and RunDag's viewportKey matches the new run", async () => {
      mockRun = makeRun({
        id: "run-1",
        graphSnapshot: makeSnapshot([{ id: "gate", executorType: "human" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "gate", status: "awaiting_human" })],
      });
      const { rerender } = renderPage("/runs/run-1");
      await waitFor(() => expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-selected", "gate"));
      expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-viewport-key", "run-1");

      mockRun = makeRun({
        id: "run-2",
        graphSnapshot: makeSnapshot([{ id: "gate2", executorType: "human" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "gate2", status: "awaiting_human" })],
      });
      rerender(
        <QueryClientProvider client={createTestQueryClient()}>
          <ActivityFeedProvider>
            <MemoryRouter initialEntries={["/runs/run-2"]}>
              <Routes>
                <Route path="/runs/:id" element={<RunMonitorPage />} />
              </Routes>
            </MemoryRouter>
          </ActivityFeedProvider>
        </QueryClientProvider>,
      );

      await waitFor(() => expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-selected", "gate2"));
      expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-viewport-key", "run-2");
    });

    it("re-clicking the already-selected node keeps it selected", async () => {
      const user = userEvent.setup();
      mockRun = makeRun({
        graphSnapshot: makeSnapshot([{ id: "a", label: "step_one" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "a", status: "pending" })],
      });
      renderPage("/runs/run-1?node=a");

      expect(screen.getByTestId("detail-panel")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "step_one" }));
      expect(screen.getByTestId("detail-panel")).toBeInTheDocument();
      expect(screen.getByTestId("rundag-stub")).toHaveAttribute("data-selected", "a");
    });

    it("the close button clears the selection", async () => {
      const user = userEvent.setup();
      mockRun = makeRun({
        graphSnapshot: makeSnapshot([{ id: "a", label: "step_one" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "a", status: "pending" })],
      });
      renderPage("/runs/run-1?node=a");

      await user.click(screen.getByTestId("detail-panel-close-button"));

      expect(screen.queryByTestId("detail-panel")).not.toBeInTheDocument();
      expect(screen.getByTestId("node-detail-empty")).toBeInTheDocument();
    });
  });

  describe("tablet tier", () => {
    beforeEach(() => {
      mockTier = "tablet";
    });

    it("renders no docked panel", () => {
      mockRun = makeRun({ graphSnapshot: makeSnapshot([{ id: "a" }]) });
      renderPage();
      expect(screen.queryByTestId("sidebar-collapse-button")).not.toBeInTheDocument();
      expect(screen.queryByTestId("node-detail-empty")).not.toBeInTheDocument();
    });

    it("selecting a node opens the mobile-detail-overlay sheet", async () => {
      const user = userEvent.setup();
      mockRun = makeRun({
        graphSnapshot: makeSnapshot([{ id: "a", label: "step_one" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "a", status: "pending" })],
      });
      renderPage();

      await user.click(screen.getByText("step_one"));
      expect(screen.getByTestId("mobile-detail-overlay")).toBeInTheDocument();
      expect(screen.getByTestId("detail-panel")).toBeInTheDocument();
    });
  });

  describe("phone tier", () => {
    beforeEach(() => {
      mockTier = "phone";
    });

    it("renders the mobile bar, not the strip or docked panel", () => {
      mockRun = makeRun({ graphSnapshot: makeSnapshot([{ id: "a" }]) });
      renderPage();
      expect(screen.getByTestId("run-summary-mobile-bar")).toBeInTheDocument();
      expect(screen.queryByTestId("run-summary")).not.toBeInTheDocument();
    });

    it("the info button opens the run-info-sheet", async () => {
      const user = userEvent.setup();
      mockRun = makeRun({ promptText: "Add a thing", graphSnapshot: makeSnapshot([{ id: "a" }]) });
      renderPage();

      await user.click(screen.getByTestId("run-info-open-button"));
      expect(screen.getByTestId("run-info-sheet")).toBeInTheDocument();
    });

    it("never auto-opens a sheet even with an awaiting gate", async () => {
      mockRun = makeRun({
        graphSnapshot: makeSnapshot([{ id: "gate", executorType: "human" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "gate", status: "awaiting_human" })],
      });
      renderPage();

      await waitFor(() => expect(screen.getByTestId("rundag-stub")).toBeInTheDocument());
      expect(screen.queryByTestId("mobile-detail-overlay")).not.toBeInTheDocument();
      expect(screen.queryByTestId("run-info-sheet")).not.toBeInTheDocument();
    });

    it("the attention button opens the node sheet", async () => {
      const user = userEvent.setup();
      mockRun = makeRun({
        graphSnapshot: makeSnapshot([{ id: "gate", label: "review_gate", executorType: "human" }]),
        nodeExecutions: [makeExecution({ templateNodeId: "gate", status: "awaiting_human" })],
      });
      renderPage();

      await user.click(screen.getByTestId("run-attention-button"));
      expect(screen.getByTestId("mobile-detail-overlay")).toBeInTheDocument();
    });
  });
});
