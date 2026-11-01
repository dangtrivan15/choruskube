import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import DetailPanel from "../DetailPanel";
import type { GateStampResponse, RunResponse } from "@/lib/types";

let mockGateStamps: GateStampResponse[] = [];

vi.mock("@/hooks/useRuns", () => ({
  useSignalNode: vi.fn(() => ({
    mutate: vi.fn(),
    isPending: false,
  })),
  useRetryNode: vi.fn(() => ({
    mutate: vi.fn(),
    isPending: false,
  })),
  useReviewHistory: vi.fn(() => ({
    data: [],
    isLoading: false,
  })),
}));

vi.mock("@/hooks/useGateStamps", () => ({
  useGateStamps: vi.fn(() => ({ data: mockGateStamps })),
}));

vi.mock("@/hooks/useArtifacts", () => ({
  useArtifacts: vi.fn(() => ({
    data: [],
    isLoading: false,
  })),
  useArtifactContent: vi.fn(() => ({
    data: undefined,
    isLoading: false,
  })),
  useArtifactsForGroups: vi.fn(() => []),
}));

vi.mock("../ArtifactList", () => ({
  default: ({ groups }: { runId: string; groups: unknown[] }) => (
    <div data-testid="artifact-list-mock">ArtifactList ({groups.length} groups)</div>
  ),
}));

vi.mock("@/hooks/useStorySpec", () => ({
  useStoryConflicts: vi.fn(() => ({ data: [] })),
}));

vi.mock("@/hooks/useExecutionLogs", () => ({
  useExecutionLogs: vi.fn(() => ({
    data: [],
    isLoading: false,
  })),
}));

function makeRun(overrides: Partial<RunResponse> = {}): RunResponse {
  return {
    id: "run-1",
    graphTemplateId: "tpl-1",
    templateName: "Test Template",
    name: null,
    status: "awaiting_human",
    externalRunId: "ext-1",
    graphVersion: 1,
    graphSnapshot: {
      nodes: [
        {
          template_node_id: "node-1",
          label: "Review Gate",
          executor_type: "human",
          is_entrypoint: false,
        },
      ],
      edges: [],
    },
    startedAt: null,
    completedAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    nodeExecutions: [
      {
        id: "exec-1",
        templateNodeId: "node-1",
        status: "awaiting_human",
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
      },
    ],
    pullRequests: [],
    promptText: null,
    task: null,
    autopilotId: null,
    storySpecVersion: null,
    softwareProject: null,
    ...overrides,
  };
}

describe("DetailPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGateStamps = [];
  });

  it("renders the node label in the header", () => {
    const run = makeRun();
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.getByTestId("detail-node-label")).toHaveTextContent("Review Gate");
  });

  it("renders HumanGatePanel for awaiting_human status without requiredArtifacts", () => {
    const run = makeRun();
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    // HumanGatePanel renders Awaiting Review badge
    expect(screen.getByText("Awaiting Review")).toBeInTheDocument();
  });

  it("hands the await_story_revision gate its run's Story", () => {
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "await_story_revision",
            executor_type: "human",
            is_entrypoint: false,
          },
        ],
        edges: [],
      },
      task: {
        id: "task-1",
        title: "Wire the payment form",
        status: "in_progress",
        softwareProject: null,
        storyId: "story-1",
        storyTitle: "Checkout",
        epicId: "epic-1",
        epicTitle: "Payments",
      },
    });
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.getByRole("link", { name: /Open the Story/ })).toHaveAttribute(
      "href",
      "/roadmap/epics/epic-1/stories/story-1"
    );
  });

  it("renders the merge-on-approval notice when the snapshot node declares merge_pull_requests", () => {
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "final_approval",
            executor_type: "human",
            is_entrypoint: false,
            config_overrides: { merge_pull_requests: "squash" },
          },
        ],
        edges: [],
      },
      pullRequests: [
        {
          id: "pr-1",
          workflowRunId: "run-1",
          gitRepoId: "repo-1",
          nodeExecutionId: null,
          prUrl: "https://github.com/org/repo/pull/1",
          prNumber: 1,
          title: "feat: x",
          repoName: "repo",
          repoUrl: "https://github.com/org/repo",
          createdAt: "2026-01-01T00:00:00Z",
          state: "open",
          mergedAt: null,
        },
      ],
    });
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.getByTestId("merge-on-approval-notice")).toBeInTheDocument();
  });

  it("renders no merge-on-approval notice for a gate without the key", () => {
    const run = makeRun();
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.queryByTestId("merge-on-approval-notice")).not.toBeInTheDocument();
  });

  it("passes requiredArtifacts to HumanGatePanel when non-null and status is awaiting_human", () => {
    const requiredArtifacts = [
      {
        nodeExecutionId: "exec-pred-1",
        nodeLabel: "Planning Node",
        artifacts: [{ name: "spec_and_plan.md", description: "Spec and plan", required: false }],
      },
    ];
    const run = makeRun({
      nodeExecutions: [
        {
          id: "exec-1",
          templateNodeId: "node-1",
          status: "awaiting_human",
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
          requiredArtifacts,
          candidateBreakdown: null,
        },
      ],
    });

    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    // ArtifactList is rendered with the requiredArtifacts groups
    expect(screen.getByTestId("artifact-list-mock")).toBeInTheDocument();
    expect(screen.getByText("ArtifactList (1 groups)")).toBeInTheDocument();
  });

  it("passes candidateBreakdown to HumanGatePanel when non-null and status is awaiting_human", () => {
    // Regression coverage: HumanGatePanel accepts a candidateBreakdown prop (the Roadmap
    // Provisioner editable breakdown), but DetailPanel previously never forwarded it from
    // the node execution — this asserts the wiring end-to-end via the real breakdown editor.
    const candidateBreakdown = {
      milestones: [],
      epics: [
        {
          title: "Add dark mode",
          description: "Support a dark theme across the app",
          motivation: "Users have asked for this repeatedly",
          repos: ["repo-a"],
          priority: "High",
          stories: [],
        },
      ],
      dependencies: [],
    };
    const run = makeRun({
      nodeExecutions: [
        {
          id: "exec-1",
          templateNodeId: "node-1",
          status: "awaiting_human",
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
          candidateBreakdown,
        },
      ],
    });

    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.getByTestId("roadmap-candidate-breakdown")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Add dark mode")).toBeInTheDocument();
  });

  it("does not leak one node's edited candidates into another node's breakdown editor", async () => {
    // Regression coverage: when a run has two nodes simultaneously awaiting_human
    // (parallel DAG branches) and the reviewer switches focus between them via
    // `nodeId`, DetailPanel previously reused the same HumanGatePanel instance
    // (no `key`), so React preserved node A's local `editedCandidates` state
    // across the prop swap — a reviewer who edited node A's breakdown and then
    // selected node B would silently submit node A's edits against node B's
    // decision. HumanGatePanel is now keyed by node execution id so switching
    // nodes remounts the panel and resets its local state.
    const user = userEvent.setup();
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "Review Gate A",
            executor_type: "human",
            is_entrypoint: false,
          },
          {
            template_node_id: "node-2",
            label: "Review Gate B",
            executor_type: "human",
            is_entrypoint: false,
          },
        ],
        edges: [],
      },
      nodeExecutions: [
        {
          id: "exec-1",
          templateNodeId: "node-1",
          status: "awaiting_human",
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
          candidateBreakdown: {
            milestones: [],
            epics: [
              { title: "Epic A", description: "d", motivation: "m", repos: ["repo-a"], priority: "High", stories: [] },
            ],
            dependencies: [],
          },
        },
        {
          id: "exec-2",
          templateNodeId: "node-2",
          status: "awaiting_human",
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
          candidateBreakdown: {
            milestones: [],
            epics: [
              { title: "Epic B", description: "d", motivation: "m", repos: ["repo-a"], priority: "High", stories: [] },
            ],
            dependencies: [],
          },
        },
      ],
    });

    const { rerender } = renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    const titleInputA = screen.getByTestId("candidate-epic-title-0") as HTMLInputElement;
    expect(titleInputA).toHaveValue("Epic A");
    await user.clear(titleInputA);
    await user.type(titleInputA, "Edited Epic A");
    expect(screen.getByTestId("candidate-epic-title-0")).toHaveValue("Edited Epic A");

    rerender(<DetailPanel run={run} nodeId="node-2" />);

    // Node B's own breakdown is shown, not node A's edited (or unedited) title.
    const titleInputB = screen.getByTestId("candidate-epic-title-0");
    expect(titleInputB).toHaveValue("Epic B");
    expect(screen.queryByDisplayValue("Edited Epic A")).not.toBeInTheDocument();
  });

  it("falls back to graph-walk predecessors when requiredArtifacts is null", () => {
    // When requiredArtifacts is null, DetailPanel calls findPredecessorOutputs
    // which walks the graph snapshot edges. With no edges, predecessorOutputs is [].
    const run = makeRun();
    // requiredArtifacts is already null in makeRun defaults
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    // In legacy mode with no predecessors, "Previous Step Output" section is absent
    expect(screen.queryByText("Previous Step Output")).not.toBeInTheDocument();
  });

  it("renders the close button only when onClose is provided", () => {
    const run = makeRun();
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);
    expect(screen.queryByTestId("detail-panel-close-button")).not.toBeInTheDocument();
  });

  it("calls onClose when the close button is clicked", () => {
    const run = makeRun();
    const onClose = vi.fn();
    renderWithProviders(<DetailPanel run={run} nodeId="node-1" onClose={onClose} />);

    expect(screen.getByTestId("detail-panel-close-button")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("detail-panel-close-button"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("shows legacy Previous Step Output when requiredArtifacts is null and predecessor exists", () => {
    // Build a run with a predecessor edge so findPredecessorOutputs returns data
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "Review Gate",
            executor_type: "human",
            is_entrypoint: false,
          },
          {
            template_node_id: "node-pred",
            label: "AI Step",
            executor_type: "ai",
            is_entrypoint: true,
          },
        ],
        edges: [
          { template_edge_id: "edge-1", source_node_id: "node-pred", target_node_id: "node-1", condition: null },
        ],
      },
      nodeExecutions: [
        {
          id: "exec-1",
          templateNodeId: "node-1",
          status: "awaiting_human",
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
        },
        {
          id: "exec-pred",
          templateNodeId: "node-pred",
          status: "completed",
          result: "AI output here",
          decision: "no_decision",
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
        },
      ],
    });

    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    // With a predecessor execution available, the legacy section should appear
    expect(screen.getByText("Previous Step Output")).toBeInTheDocument();
  });

  it("renders Approve for a Roadmap-Human-Gate-shaped node (terminal decision, no approved edge)", () => {
    // Mirrors the Roadmap Provisioner's human gate: a single outgoing `rejected`
    // edge plus `approved` only reachable via terminal_decisions — the server's
    // decision_options field is the only source for "approved" here, since no
    // edge carries that condition.
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "Roadmap Human Gate",
            executor_type: "human",
            is_entrypoint: false,
            config_overrides: { terminal_decisions: ["approved"] },
            decision_options: ["rejected", "approved"],
          },
        ],
        edges: [
          { template_edge_id: "edge-1", source_node_id: "node-1", target_node_id: "node-1", condition: "rejected" },
        ],
      },
    });

    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.getByTestId("gate-approve-button")).toBeInTheDocument();
    expect(screen.getByTestId("gate-reject-button")).toBeInTheDocument();
  });

  it("renders Approve and Reject for a plain two-edge gate (regression, no terminal_decisions)", () => {
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "Review Gate",
            executor_type: "human",
            is_entrypoint: false,
            decision_options: ["approved", "rejected"],
          },
        ],
        edges: [
          { template_edge_id: "edge-1", source_node_id: "node-1", target_node_id: "node-1", condition: "approved" },
          { template_edge_id: "edge-2", source_node_id: "node-1", target_node_id: "node-1", condition: "rejected" },
        ],
      },
    });

    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.getByTestId("gate-approve-button")).toBeInTheDocument();
    expect(screen.getByTestId("gate-reject-button")).toBeInTheDocument();
  });

  describe("a gate that is finalizing its approval", () => {
    function makeFinalizingRun(): RunResponse {
      const base = makeRun();
      return makeRun({
        graphSnapshot: {
          nodes: [
            {
              template_node_id: "node-1",
              label: "final_approval",
              executor_type: "human",
              is_entrypoint: false,
              decision_options: ["approved", "rejected"],
            },
          ],
          edges: [],
        },
        nodeExecutions: [{ ...base.nodeExecutions[0], status: "finalizing" }],
      });
    }

    it("renders the finalizing panel and no decision buttons", () => {
      renderWithProviders(<DetailPanel run={makeFinalizingRun()} nodeId="node-1" />);

      expect(screen.getByTestId("gate-finalizing")).toBeInTheDocument();
      expect(screen.getByTestId("detail-node-status")).toHaveTextContent("finalizing");
      expect(screen.queryByTestId("gate-approve-button")).not.toBeInTheDocument();
      expect(screen.queryByTestId("gate-reject-button")).not.toBeInTheDocument();
      expect(screen.queryByText("Awaiting Review")).not.toBeInTheDocument();
    });

    it("marks the header with the in-progress icon, as for a running node", () => {
      renderWithProviders(<DetailPanel run={makeFinalizingRun()} nodeId="node-1" />);

      const header = screen.getByTestId("detail-node-label").closest("div.border-b");
      expect(header?.querySelector("svg.animate-pulse")).not.toBeNull();
    });

    it("shows the round of the gate's latest stamp, not another gate's", () => {
      const stamp = (overrides: Partial<GateStampResponse>): GateStampResponse => ({
        id: "stamp-1",
        gateTemplateNodeId: "node-1",
        gateLabel: "final_approval",
        status: "finalizing",
        recheckRounds: 1,
        maxRecheckRounds: 2,
        voidReason: null,
        supersededByReviewer: false,
        parkedNodeExecutionId: null,
        createdAt: "2026-10-11T00:00:00Z",
        updatedAt: "2026-10-11T00:00:00Z",
        ...overrides,
      });
      mockGateStamps = [stamp({}), stamp({ id: "stamp-2", gateLabel: "other_gate", recheckRounds: 2 })];

      renderWithProviders(<DetailPanel run={makeFinalizingRun()} nodeId="node-1" />);

      expect(screen.getByTestId("gate-finalizing")).toHaveTextContent("Re-check round 1 of 2.");
    });
  });

  it("renders an Effort badge carrying the node's effort value", () => {
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "Implement",
            executor_type: "ai",
            is_entrypoint: false,
            effort: "xhigh",
          },
        ],
        edges: [],
      },
      nodeExecutions: [
        {
          id: "exec-1",
          templateNodeId: "node-1",
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
        },
      ],
    });

    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.getByText("Effort: xhigh")).toBeInTheDocument();
  });

  describe("Supervisor escalation gate", () => {
    function makeSupervisorRun(escalation?: RunResponse["nodeExecutions"][number]["escalation"]) {
      return makeRun({
        graphSnapshot: {
          nodes: [
            {
              template_node_id: "node-1",
              label: "Supervisor",
              executor_type: "human",
              is_entrypoint: false,
              decision_options: ["route:qa_review", "route:implement"],
            },
          ],
          edges: [],
        },
        nodeExecutions: [
          {
            id: "exec-1",
            templateNodeId: "node-1",
            status: "awaiting_human",
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
            escalation,
          },
        ],
      });
    }

    it("delegates to EscalationGatePanel for a route:* gate carrying escalation data", () => {
      const run = makeSupervisorRun({
        escalatorLabel: "Code Review",
        escalatorExecId: "escalator-exec-1",
        escalatorLoopGroup: "loop-a",
        category: "blocked_external",
        summary: "CI runner is wedged and cannot be reached.",
      });

      renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

      expect(screen.getByTestId("escalation-gate-panel")).toBeInTheDocument();
      expect(screen.getByText("Code Review")).toBeInTheDocument();
      expect(screen.getByText("CI runner is wedged and cannot be reached.")).toBeInTheDocument();
    });

    // Defensive coverage for a rolling deploy: an older API pod may omit `escalation`
    // from NodeExecutionResponse (it's an optional field precisely for that case — see
    // its doc comment in types.ts). The gate must still be routable even then — the
    // picker degrades gracefully rather than crashing or hiding.
    it("still renders a functional picker when the node execution carries no escalation data", () => {
      const run = makeSupervisorRun(undefined);

      renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

      expect(screen.getByTestId("escalation-gate-panel")).toBeInTheDocument();
      expect(screen.getByTestId("escalation-target-picker")).toBeInTheDocument();
      expect(screen.getByTestId("escalation-confirm-button")).toBeInTheDocument();
    });
  });

  it("does not render an Effort badge when the node has no effort override", () => {
    const run = makeRun({
      graphSnapshot: {
        nodes: [
          {
            template_node_id: "node-1",
            label: "Implement",
            executor_type: "ai",
            is_entrypoint: false,
          },
        ],
        edges: [],
      },
      nodeExecutions: [
        {
          id: "exec-1",
          templateNodeId: "node-1",
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
        },
      ],
    });

    renderWithProviders(<DetailPanel run={run} nodeId="node-1" />);

    expect(screen.queryByText(/^Effort: /)).not.toBeInTheDocument();
  });
});
