import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ApprovalsPage from "@/pages/ApprovalsPage";

vi.mock("@/hooks/usePendingGates", () => ({
  usePendingGates: vi.fn(),
  usePendingGatesCount: vi.fn().mockReturnValue({ data: { count: 0 } }),
  useSignalFromDashboard: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  }),
}));

vi.mock("@/hooks/usePendingGatesSubscription", () => ({
  usePendingGatesSubscription: vi.fn(),
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
}));

vi.mock("@/components/runs/LiveChatPanel", () => ({
  default: ({ runId, nodeExecId }: { runId: string; nodeExecId: string; nodeLabel: string }) => (
    <div data-testid="live-chat-panel-mock" data-run-id={runId} data-node-exec-id={nodeExecId}>
      LiveChatPanel
    </div>
  ),
}));

vi.mock("@/components/runs/ArtifactList", () => ({
  default: ({ groups }: { runId: string; groups: unknown[] }) => (
    <div data-testid="artifact-list-mock">ArtifactList ({groups.length} groups)</div>
  ),
}));

import { usePendingGates } from "@/hooks/usePendingGates";
import { useArtifacts } from "@/hooks/useArtifacts";

const mockUsePendingGates = usePendingGates as ReturnType<typeof vi.fn>;
const mockUseArtifacts = useArtifacts as ReturnType<typeof vi.fn>;

describe("ApprovalsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows loading skeletons when loading", () => {
    mockUsePendingGates.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.getByText("Approvals")).toBeInTheDocument();
  });

  it("shows error message on fetch error", () => {
    mockUsePendingGates.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.getByText(/Failed to load pending approvals/)).toBeInTheDocument();
  });

  it("shows empty state when no pending gates", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.getByText("No pending approvals")).toBeInTheDocument();
    expect(screen.getByText(/All human gates have been resolved/)).toBeInTheDocument();
  });

  it("renders gate cards when there are pending gates", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Code Review Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: 3600,
          waitingSince: new Date(Date.now() - 300_000).toISOString(),
          status: "awaiting_human",
          predecessorOutputs: [
            {
              templateNodeId: "node-pred-1",
              label: "AI Code Gen",
              result: "Generated some code",
              artifactRefs: "{}",
              nodeExecutionId: "pred-exec-1",
            },
          ],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.getByText("Human Review")).toBeInTheDocument();
    expect(screen.getByText("Code Review Workflow")).toBeInTheDocument();
    expect(screen.getByText("Iteration 1")).toBeInTheDocument();
    expect(screen.getByText("View Run")).toBeInTheDocument();
    expect(screen.getByText("Approve")).toBeInTheDocument();
    expect(screen.getByText("Reject")).toBeInTheDocument();
  });

  it("renders multiple gate cards", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Workflow A",
          nodeLabel: "Gate 1",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
        },
        {
          nodeExecutionId: "exec-2",
          runId: "run-2",
          runStatus: "running",
          runName: "Workflow B",
          nodeLabel: "Gate 2",
          iteration: 2,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
        },
      ], totalElements: 2, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.getByText("Gate 1")).toBeInTheDocument();
    expect(screen.getByText("Gate 2")).toBeInTheDocument();
    expect(screen.getByText("2 pending")).toBeInTheDocument();
  });

  it("renders predecessor outputs with markdown formatting when expanded", async () => {
    const user = userEvent.setup();
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Review Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [
            {
              templateNodeId: "node-1",
              label: "AI Node",
              result: "## Analysis\n\nFound **3 issues**.",
              artifactRefs: "{}",
              nodeExecutionId: "pred-exec-2",
            },
          ],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    // Expand the predecessor outputs
    await user.click(screen.getByText(/Previous Step Output/));

    expect(screen.getByText("Analysis").tagName).toBe("H2");
    expect(screen.getByText("3 issues").tagName).toBe("STRONG");
  });

  it("renders artifact browser for predecessor with nodeExecutionId", async () => {
    const user = userEvent.setup();
    mockUseArtifacts.mockReturnValue({
      data: [
        { name: "spec.md", size: 1024, lastModified: "2026-04-01T00:00:00Z" },
        { name: "diff.patch", size: 512, lastModified: "2026-04-01T00:00:00Z" },
      ],
      isLoading: false,
    });

    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Review Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [
            {
              templateNodeId: "node-1",
              label: "AI Node",
              result: "Some output",
              artifactRefs: "{}",
              nodeExecutionId: "pred-exec-1",
            },
          ],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    // Expand the predecessor outputs
    await user.click(screen.getByText(/Previous Step Output/));

    // Artifact browser should be visible with artifacts
    expect(screen.getByText("Artifacts")).toBeInTheDocument();
    expect(screen.getByText("spec.md")).toBeInTheDocument();
    expect(screen.getByText("diff.patch")).toBeInTheDocument();
  });

  it("does not render artifact browser when nodeExecutionId is null", async () => {
    const user = userEvent.setup();
    mockUseArtifacts.mockReturnValue({
      data: [],
      isLoading: false,
    });

    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Review Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [
            {
              templateNodeId: "node-1",
              label: "AI Node",
              result: "Some output",
              artifactRefs: "{}",
              nodeExecutionId: null,
            },
          ],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    // Expand the predecessor outputs
    await user.click(screen.getByText(/Previous Step Output/));

    // Artifact browser should NOT be rendered since nodeExecutionId is null
    expect(screen.queryByText("Artifacts")).not.toBeInTheDocument();
  });

  it("renders LiveChatPanel in each gate card", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Workflow A",
          nodeLabel: "Gate 1",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    const liveChatPanel = screen.getByTestId("live-chat-panel-mock");
    expect(liveChatPanel).toBeInTheDocument();
    expect(liveChatPanel).toHaveAttribute("data-run-id", "run-1");
    expect(liveChatPanel).toHaveAttribute("data-node-exec-id", "exec-1");
  });

  it("shows Live Chat badge and disables buttons when status is live_chat", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "running",
          runName: "Chat Workflow",
          nodeLabel: "Chat Gate",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: new Date(Date.now() - 60_000).toISOString(),
          status: "live_chat",
          predecessorOutputs: [],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    // Live Chat badge should be visible
    expect(screen.getByTestId("live-chat-badge")).toBeInTheDocument();
    expect(screen.getByText("Live Chat")).toBeInTheDocument();

    // Approve and Reject buttons should be disabled
    expect(screen.getByTestId("gate-card-approve-button")).toBeDisabled();
    expect(screen.getByTestId("gate-card-reject-button")).toBeDisabled();

    // Feedback textarea should be disabled
    expect(screen.getByTestId("gate-card-feedback")).toBeDisabled();
  });

  it("does not show Live Chat badge when status is awaiting_human", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Workflow A",
          nodeLabel: "Gate 1",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.queryByTestId("live-chat-badge")).not.toBeInTheDocument();
    // Approve button should be enabled
    expect(screen.getByTestId("gate-card-approve-button")).not.toBeDisabled();
  });

  it("renders FileUploadZone in each GateCard", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Workflow A",
          nodeLabel: "Gate 1",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.getByTestId("file-upload-zone")).toBeInTheDocument();
    expect(screen.getByText("Attach files — drag & drop or click to browse")).toBeInTheDocument();
  });

  it("gate with requiredArtifacts shows ArtifactList section", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Artifact Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
          requiredArtifacts: [
            { nodeExecutionId: "exec-pred-1", nodeLabel: "Planning Node", artifacts: [{ name: "plan.md", description: null }] },
          ],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.getByTestId("artifact-list-mock")).toBeInTheDocument();
    expect(screen.getByText("ArtifactList (1 groups)")).toBeInTheDocument();
  });

  it("gate with requiredArtifacts: null does NOT render ArtifactList", () => {
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Legacy Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
          requiredArtifacts: null,
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    expect(screen.queryByTestId("artifact-list-mock")).not.toBeInTheDocument();
  });

  it("legacy gate: ArtifactBrowser still rendered per predecessor when requiredArtifacts is null", async () => {
    const user = userEvent.setup();
    mockUseArtifacts.mockReturnValue({
      data: [{ name: "file.txt", size: 100, lastModified: "2026-01-01T00:00:00Z" }],
      isLoading: false,
    });
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Legacy Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [
            {
              templateNodeId: "node-1",
              label: "AI Node",
              result: "Some output",
              artifactRefs: "{}",
              nodeExecutionId: "pred-exec-1",
            },
          ],
          requiredArtifacts: null,
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    await user.click(screen.getByText(/Previous Step Output/));

    // ArtifactBrowser should be rendered because requiredArtifacts is null
    expect(screen.getByTestId("artifact-browser")).toBeInTheDocument();
    expect(screen.queryByTestId("artifact-list-mock")).not.toBeInTheDocument();
  });

  it("gate with requiredArtifacts does NOT show per-predecessor ArtifactBrowser", async () => {
    const user = userEvent.setup();
    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Artifact Workflow",
          nodeLabel: "Human Review",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [
            {
              templateNodeId: "node-1",
              label: "AI Node",
              result: "Some output",
              artifactRefs: "{}",
              nodeExecutionId: "pred-exec-1",
            },
          ],
          requiredArtifacts: [
            { nodeExecutionId: "exec-pred-1", nodeLabel: "AI Node", artifacts: [] },
          ],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<ApprovalsPage />);

    await user.click(screen.getByText(/Previous Step Output/));

    // ArtifactBrowser should NOT appear because requiredArtifacts is non-null
    expect(screen.queryByTestId("artifact-browser")).not.toBeInTheDocument();
    // ArtifactList mock should appear
    expect(screen.getByTestId("artifact-list-mock")).toBeInTheDocument();
  });

  it("passes selected files to useSignalFromDashboard mutation", async () => {
    const mockMutate = vi.fn();
    const { useSignalFromDashboard } = await import("@/hooks/usePendingGates");
    (useSignalFromDashboard as ReturnType<typeof vi.fn>).mockReturnValue({
      mutate: mockMutate,
      isPending: false,
    });

    mockUsePendingGates.mockReturnValue({
      data: { content: [
        {
          nodeExecutionId: "exec-1",
          runId: "run-1",
          runStatus: "awaiting_human",
          runName: "Workflow A",
          nodeLabel: "Gate 1",
          iteration: 1,
          timeoutSeconds: null,
          waitingSince: null,
          status: "awaiting_human",
          predecessorOutputs: [],
        },
      ], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
      isError: false,
    });

    const user = userEvent.setup();
    renderWithProviders(<ApprovalsPage />);

    const file = new File(["data"], "report.pdf", { type: "application/pdf" });
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(fileInput, file);

    await user.click(screen.getByTestId("gate-card-approve-button"));

    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        runId: "run-1",
        nodeExecId: "exec-1",
        decision: "approved",
        files: [file],
      }),
      expect.objectContaining({ onSuccess: expect.any(Function) })
    );
  });

  describe("roadmap candidate breakdown", () => {
    const candidateBreakdown = {
      milestones: [],
      epics: [
        {
          title: "Add dark mode",
          description: "Support a dark theme",
          motivation: "Users asked for it",
          repos: ["repo-a"],
          priority: "High",
          stories: [
            {
              title: "Theme toggle",
              description: "Add a toggle",
              tasks: [{ title: "Build toggle", description: "New component" }],
            },
          ],
        },
      ],
      dependencies: [],
    };

    function renderWithBreakdownGate(breakdown: unknown) {
      mockUsePendingGates.mockReturnValue({
        data: {
          content: [
            {
              nodeExecutionId: "exec-1",
              runId: "run-1",
              runStatus: "awaiting_human",
              runName: "Roadmap Provisioner Run",
              nodeLabel: "review_candidates",
              iteration: 1,
              timeoutSeconds: null,
              waitingSince: null,
              status: "awaiting_human",
              predecessorOutputs: [],
              candidateBreakdown: breakdown,
            },
          ],
          totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false,
        },
        isLoading: false,
        isError: false,
      });
      renderWithProviders(<ApprovalsPage />);
    }

    it("renders the editable breakdown when gate.candidateBreakdown is present", () => {
      renderWithBreakdownGate(candidateBreakdown);

      expect(screen.getByTestId("roadmap-candidate-breakdown")).toBeInTheDocument();
      expect(screen.getByDisplayValue("Add dark mode")).toBeInTheDocument();
    });

    it("does not render the breakdown when gate.candidateBreakdown is null", () => {
      renderWithBreakdownGate(null);

      expect(screen.queryByTestId("roadmap-candidate-breakdown")).not.toBeInTheDocument();
    });

    it("includes editedCandidates in the mutate payload on Approve, reflecting edits", async () => {
      const mockMutate = vi.fn();
      const { useSignalFromDashboard } = await import("@/hooks/usePendingGates");
      (useSignalFromDashboard as ReturnType<typeof vi.fn>).mockReturnValue({
        mutate: mockMutate,
        isPending: false,
      });

      const user = userEvent.setup();
      renderWithBreakdownGate(candidateBreakdown);

      const titleInput = screen.getByDisplayValue("Add dark mode");
      await user.clear(titleInput);
      await user.type(titleInput, "Add dark and light mode");

      await user.click(screen.getByTestId("gate-card-approve-button"));

      expect(mockMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          runId: "run-1",
          nodeExecId: "exec-1",
          decision: "approved",
          editedCandidates: expect.objectContaining({
            epics: [expect.objectContaining({ title: "Add dark and light mode" })],
          }),
        }),
        expect.objectContaining({ onSuccess: expect.any(Function) })
      );
    });

    it("does not include editedCandidates in the mutate payload when candidateBreakdown was null", async () => {
      const mockMutate = vi.fn();
      const { useSignalFromDashboard } = await import("@/hooks/usePendingGates");
      (useSignalFromDashboard as ReturnType<typeof vi.fn>).mockReturnValue({
        mutate: mockMutate,
        isPending: false,
      });

      const user = userEvent.setup();
      renderWithBreakdownGate(null);

      await user.click(screen.getByTestId("gate-card-approve-button"));

      const [payload] = mockMutate.mock.calls[0];
      expect(Object.prototype.hasOwnProperty.call(payload, "editedCandidates")).toBe(false);
    });
  });

  describe("Supervisor escalation gate from approvals dashboard", () => {
    function renderWithEscalationGate(overrides: Record<string, unknown> = {}) {
      mockUsePendingGates.mockReturnValue({
        data: {
          content: [
            {
              nodeExecutionId: "exec-esc-1",
              runId: "run-esc-1",
              runStatus: "awaiting_human",
              runName: "Feature Dev Run",
              nodeLabel: "Supervisor",
              iteration: 1,
              timeoutSeconds: null,
              waitingSince: null,
              status: "awaiting_human",
              predecessorOutputs: [],
              decisionOptions: ["route:qa_review", "route:implement"],
              escalation: {
                escalatorLabel: "Code Review",
                escalatorExecId: "escalator-exec-1",
                escalatorLoopGroup: "loop-a",
                category: "blocked_external",
                summary: "CI runner is wedged and cannot be reached.",
              },
              ...overrides,
            },
          ],
          totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false,
        },
        isLoading: false,
        isError: false,
      });
      renderWithProviders(<ApprovalsPage />);
    }

    it("delegates to EscalationGatePanel instead of DecisionButtons", () => {
      renderWithEscalationGate();

      expect(screen.getByTestId("escalation-gate-panel")).toBeInTheDocument();
      expect(screen.queryByTestId("gate-card-approve-button")).not.toBeInTheDocument();
    });

    it("renders the escalator context from gate.escalation", () => {
      renderWithEscalationGate();

      expect(screen.getByText("Code Review")).toBeInTheDocument();
      expect(screen.getByText("CI runner is wedged and cannot be reached.")).toBeInTheDocument();
    });

    // Regression coverage: ArtifactResolutionService synthesises exactly one required-artifact
    // group for every Supervisor gate — the escalating node's escalation.md, flagged required.
    // GateCard already threads `gate.requiredArtifacts` into the ordinary-gate ArtifactList; it
    // must also thread it into EscalationGatePanel, not drop it on the floor.
    it("threads gate.requiredArtifacts into EscalationGatePanel (the escalator's required escalation.md)", () => {
      renderWithEscalationGate({
        requiredArtifacts: [
          {
            nodeExecutionId: "escalator-exec-1",
            nodeLabel: "Code Review",
            artifacts: [
              { name: "escalation.md", description: "Why this run was escalated to the Supervisor", required: true },
            ],
          },
        ],
      });

      expect(screen.getByTestId("artifact-list-mock")).toBeInTheDocument();
      expect(screen.getByText("ArtifactList (1 groups)")).toBeInTheDocument();
    });

    it("submits the chosen route: decision with human_guidance.md attached", async () => {
      const mockMutate = vi.fn();
      const { useSignalFromDashboard } = await import("@/hooks/usePendingGates");
      (useSignalFromDashboard as ReturnType<typeof vi.fn>).mockReturnValue({
        mutate: mockMutate,
        isPending: false,
      });

      const user = userEvent.setup();
      renderWithEscalationGate();

      await user.type(screen.getByTestId("escalation-guidance-input"), "Route to QA");
      await user.click(screen.getByTestId("escalation-target-picker"));
      await user.click(screen.getByTestId("escalation-target-option-qa_review"));
      await user.click(screen.getByTestId("escalation-confirm-button"));

      expect(mockMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          runId: "run-esc-1",
          nodeExecId: "exec-esc-1",
          decision: "route:qa_review",
          files: expect.arrayContaining([
            expect.objectContaining({ name: "human_guidance.md" }),
          ]),
        }),
        expect.objectContaining({ onSuccess: expect.any(Function) })
      );
    });
  });

  describe("v23 spec gate from approvals dashboard (regression: 500 on Reject)", () => {
    function renderWithV23Gate() {
      mockUsePendingGates.mockReturnValue({
        data: {
          content: [
            {
              nodeExecutionId: "exec-v23",
              runId: "run-v23",
              runStatus: "awaiting_human",
              runName: "Spec Approval Run",
              nodeLabel: "approve_spec_and_plan",
              iteration: 1,
              timeoutSeconds: null,
              waitingSince: null,
              status: "awaiting_human",
              predecessorOutputs: [],
              decisionOptions: ["approved", "rereview", "redraft"],
            },
          ],
          totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false,
        },
        isLoading: false,
        isError: false,
      });
      renderWithProviders(<ApprovalsPage />);
    }

    it("renders Approve / Re-review / Redraft buttons (no Reject) for v23 spec gates", () => {
      renderWithV23Gate();
      expect(screen.getByTestId("gate-card-approve-button")).toBeInTheDocument();
      expect(screen.getByTestId("gate-card-rereview-button")).toBeInTheDocument();
      expect(screen.getByTestId("gate-card-redraft-button")).toBeInTheDocument();
      expect(screen.queryByTestId("gate-card-reject-button")).not.toBeInTheDocument();
    });

    it("submits 'rereview' with human_guidance.md when Re-review is clicked", async () => {
      const mockMutate = vi.fn();
      const { useSignalFromDashboard } = await import("@/hooks/usePendingGates");
      (useSignalFromDashboard as ReturnType<typeof vi.fn>).mockReturnValue({
        mutate: mockMutate,
        isPending: false,
      });
      renderWithV23Gate();

      const user = userEvent.setup();
      await user.type(
        screen.getByPlaceholderText("Provide feedback for the AI agent..."),
        "Tighten"
      );
      await user.click(screen.getByTestId("gate-card-rereview-button"));

      expect(mockMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          decision: "rereview",
          files: expect.arrayContaining([
            expect.objectContaining({ name: "human_guidance.md" }),
          ]),
        }),
        expect.any(Object)
      );
    });
  });
});
