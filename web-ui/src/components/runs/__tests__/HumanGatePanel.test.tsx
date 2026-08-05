import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import HumanGatePanel from "../HumanGatePanel";

const mockMutate = vi.fn();

vi.mock("@/hooks/useRuns", () => ({
  useSignalNode: vi.fn(() => ({
    mutate: mockMutate,
    isPending: false,
  })),
  useReviewHistory: vi.fn(() => ({
    data: [],
    isLoading: false,
  })),
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

vi.mock("@/hooks/useLiveChat", () => ({
  useLiveChatSession: vi.fn(() => ({
    data: undefined,
    isLoading: false,
  })),
  useStartLiveChat: vi.fn(() => ({
    mutate: vi.fn(),
    isPending: false,
  })),
  useCompleteLiveChat: vi.fn(() => ({
    mutate: vi.fn(),
    isPending: false,
  })),
  useSendLiveChatMessage: vi.fn(() => ({
    mutate: vi.fn(),
    isPending: false,
  })),
  useLiveChatMessages: vi.fn(() => ({
    messages: [],
    addMessage: vi.fn(),
    clearMessages: vi.fn(),
  })),
}));

describe("HumanGatePanel", () => {
  const defaultProps = {
    runId: "run-1",
    nodeExecId: "exec-1",
    loopGroup: "group-1",
    nodeLabel: "Review Node",
    iteration: 2,
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders node label and iteration badge", () => {
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    expect(screen.getByText("Review Node")).toBeInTheDocument();
    expect(screen.getByText("Iteration 2")).toBeInTheDocument();
  });

  it("renders Awaiting Review badge", () => {
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    expect(screen.getByText("Awaiting Review")).toBeInTheDocument();
  });

  it("renders Approve and Reject buttons", () => {
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    expect(screen.getByText("Approve")).toBeInTheDocument();
    expect(screen.getByText("Reject")).toBeInTheDocument();
  });

  it("renders feedback textarea", () => {
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    expect(screen.getByPlaceholderText("Provide feedback for the AI agent...")).toBeInTheDocument();
  });

  it("calls signalNode.mutate with 'approved' when Approve is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    await user.click(screen.getByText("Approve"));

    expect(mockMutate).toHaveBeenCalledWith(
      { nodeExecId: "exec-1", decision: "approved", feedback: "", files: [] },
      expect.objectContaining({ onSuccess: expect.any(Function) })
    );
  });

  it("calls signalNode.mutate with 'rejected' and feedback when Reject is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    const textarea = screen.getByPlaceholderText("Provide feedback for the AI agent...");
    await user.type(textarea, "Needs more detail");
    await user.click(screen.getByText("Reject"));

    expect(mockMutate).toHaveBeenCalledWith(
      { nodeExecId: "exec-1", decision: "rejected", feedback: "Needs more detail", files: [] },
      expect.objectContaining({ onSuccess: expect.any(Function) })
    );
  });

  it("disables Reject button when feedback is empty", () => {
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    expect(screen.getByText("Reject").closest("button")).toBeDisabled();
  });

  it("enables Reject button when feedback is provided", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    const textarea = screen.getByPlaceholderText("Provide feedback for the AI agent...");
    await user.type(textarea, "feedback text");

    expect(screen.getByText("Reject").closest("button")).not.toBeDisabled();
  });

  it("renders predecessor outputs when provided", () => {
    const props = {
      ...defaultProps,
      predecessorOutputs: [
        { nodeLabel: "Previous Node", result: "some output data", execId: "pred-exec-1" },
      ],
    };
    renderWithProviders(<HumanGatePanel {...props} />);

    expect(screen.getByText("Previous Step Output")).toBeInTheDocument();
    expect(screen.getByText("some output data")).toBeInTheDocument();
  });

  it("shows 'No output available' when predecessor result is null", () => {
    const props = {
      ...defaultProps,
      predecessorOutputs: [
        { nodeLabel: "Previous Node", result: null, execId: null },
      ],
    };
    renderWithProviders(<HumanGatePanel {...props} />);

    expect(screen.getByText("No output available")).toBeInTheDocument();
  });

  it("renders predecessor markdown output with formatting", () => {
    const props = {
      ...defaultProps,
      predecessorOutputs: [
        { nodeLabel: "AI Node", result: "## Summary\n\nThis is **bold** output.", execId: "pred-exec-1" },
      ],
    };
    renderWithProviders(<HumanGatePanel {...props} />);

    expect(screen.getByText("Summary").tagName).toBe("H2");
    expect(screen.getByText("bold").tagName).toBe("STRONG");
  });

  it("shows Raw/Rendered toggle for predecessor output", async () => {
    const user = userEvent.setup();
    const props = {
      ...defaultProps,
      predecessorOutputs: [
        { nodeLabel: "AI Node", result: "**formatted**", execId: "pred-exec-1" },
      ],
    };
    renderWithProviders(<HumanGatePanel {...props} />);

    expect(screen.getByText("Raw")).toBeInTheDocument();

    await user.click(screen.getByText("Raw"));
    expect(screen.getByText("Rendered")).toBeInTheDocument();
    expect(screen.getByText("**formatted**")).toBeInTheDocument();
  });

  it("renders LiveChatPanel within HumanGatePanel", () => {
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    expect(screen.getByTestId("start-live-chat-button")).toBeInTheDocument();
  });

  it("shows chat transcript when nodeResult is provided", () => {
    renderWithProviders(
      <HumanGatePanel
        {...defaultProps}
        nodeResult={"**Human:** Hello\n\n**AI:** Hi there"}
      />
    );

    expect(screen.getByText("Chat Transcript")).toBeInTheDocument();
  });

  it("does not show transcript section when nodeResult is null", () => {
    renderWithProviders(
      <HumanGatePanel {...defaultProps} nodeResult={null} />
    );

    expect(screen.queryByText("Chat Transcript")).not.toBeInTheDocument();
  });

  it("renders FileUploadZone within the panel", () => {
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    expect(screen.getByTestId("file-upload-zone")).toBeInTheDocument();
    expect(screen.getByText("Attach files — drag & drop or click to browse")).toBeInTheDocument();
  });

  it("renders ArtifactList when requiredArtifacts is non-null with groups", () => {
    const requiredArtifacts = [
      {
        nodeExecutionId: "exec-pred-1",
        nodeLabel: "Planning Node",
        artifacts: [{ name: "spec_and_plan.md", description: "Spec and plan", required: false }],
      },
    ];
    renderWithProviders(
      <HumanGatePanel {...defaultProps} requiredArtifacts={requiredArtifacts} />
    );

    expect(screen.getByTestId("artifact-list-mock")).toBeInTheDocument();
    expect(screen.getByText("ArtifactList (1 groups)")).toBeInTheDocument();
  });

  it("does not render accordion group labels when using ArtifactList", () => {
    const requiredArtifacts = [
      {
        nodeExecutionId: "exec-pred-1",
        nodeLabel: "Planning Node",
        artifacts: [{ name: "spec_and_plan.md", description: "Spec and plan", required: false }],
      },
    ];
    renderWithProviders(
      <HumanGatePanel {...defaultProps} requiredArtifacts={requiredArtifacts} />
    );

    // Old accordion did not use ArtifactList; now it does — no per-group button
    expect(screen.queryByText("Waiting for Planning Node to complete.")).not.toBeInTheDocument();
  });

  it("renders nothing for artifacts section when requiredArtifacts is empty array", () => {
    renderWithProviders(
      <HumanGatePanel {...defaultProps} requiredArtifacts={[]} />
    );

    // With empty non-null array, no group labels should be visible
    expect(screen.queryByText("Planning Node")).not.toBeInTheDocument();
  });

  it("falls back to predecessor outputs when requiredArtifacts is null", () => {
    const predecessorOutputs = [
      { nodeLabel: "Previous Node", result: "predecessor result", execId: "pred-exec-1" },
    ];
    renderWithProviders(
      <HumanGatePanel
        {...defaultProps}
        requiredArtifacts={null}
        predecessorOutputs={predecessorOutputs}
      />
    );

    expect(screen.getByText("Previous Step Output")).toBeInTheDocument();
    expect(screen.getByText("predecessor result")).toBeInTheDocument();
  });

  it("passes selected files to signalMutation.mutate as files param", async () => {
    const user = userEvent.setup();
    renderWithProviders(<HumanGatePanel {...defaultProps} />);

    const file = new File(["evidence"], "evidence.png", { type: "image/png" });
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await user.upload(fileInput, file);

    await user.click(screen.getByText("Approve"));

    expect(mockMutate).toHaveBeenCalledWith(
      {
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "",
        files: [file],
      },
      expect.objectContaining({ onSuccess: expect.any(Function) })
    );
  });

  describe("v23 Approve Spec & Plan gate", () => {
    const approveSpecProps = {
      ...defaultProps,
      nodeLabel: "approve_spec_and_plan",
      decisionOptions: ["approved", "rereview", "redraft"],
    };

    it("renders Approve / Re-review / Redraft buttons (no Reject)", () => {
      renderWithProviders(<HumanGatePanel {...approveSpecProps} />);
      expect(screen.getByText("Approve")).toBeInTheDocument();
      expect(screen.getByText("Re-review")).toBeInTheDocument();
      expect(screen.getByText("Redraft")).toBeInTheDocument();
      expect(screen.queryByText("Reject")).not.toBeInTheDocument();
    });

    it("submits 'rereview' with human_guidance.md attached when guidance is typed", async () => {
      const user = userEvent.setup();
      renderWithProviders(<HumanGatePanel {...approveSpecProps} />);

      const textarea = screen.getByPlaceholderText("Provide feedback for the AI agent...");
      await user.type(textarea, "Tighten §3 architecture");
      await user.click(screen.getByText("Re-review"));

      expect(mockMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          decision: "rereview",
          feedback: "Tighten §3 architecture",
          files: expect.arrayContaining([
            expect.objectContaining({ name: "human_guidance.md" }),
          ]),
        }),
        expect.any(Object),
      );
    });

    it("submits 'redraft' for the Redraft button with guidance attached", async () => {
      const user = userEvent.setup();
      renderWithProviders(<HumanGatePanel {...approveSpecProps} />);
      // redraft requires feedback (same policy as rejected/rereview)
      const textarea = screen.getByPlaceholderText("Provide feedback for the AI agent...");
      await user.type(textarea, "Full re-author needed — drop §2 entirely");
      await user.click(screen.getByText("Redraft"));
      expect(mockMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          decision: "redraft",
          files: expect.arrayContaining([
            expect.objectContaining({ name: "human_guidance.md" }),
          ]),
        }),
        expect.any(Object),
      );
    });

    it("disables Redraft until feedback is typed", async () => {
      renderWithProviders(<HumanGatePanel {...approveSpecProps} />);
      expect(screen.getByTestId("gate-redraft-button")).toBeDisabled();
    });

    it("relabels buttons under alternative_proposal trigger", () => {
      renderWithProviders(
        <HumanGatePanel
          {...approveSpecProps}
          triggerDecision="need_human_decision:alternative_proposal"
        />,
      );
      expect(screen.getByText("Stay with current spec")).toBeInTheDocument();
      expect(screen.getByText("Accept alternative")).toBeInTheDocument();
      expect(screen.getByText("Alternative design proposed")).toBeInTheDocument();
    });

    it("renders review_conflict banner when triggered by it", () => {
      renderWithProviders(
        <HumanGatePanel
          {...approveSpecProps}
          triggerDecision="need_human_decision:review_conflict"
        />,
      );
      expect(screen.getByText("Review conflict detected")).toBeInTheDocument();
    });
  });

  describe("roadmap candidate breakdown", () => {
    const candidateBreakdown = [
      {
        title: "Add dark mode",
        description: "Support a dark theme across the app",
        motivation: "Users have asked for this repeatedly",
        repos: ["repo-a", "repo-b"],
        priority: "High",
        stories: [
          {
            title: "Theme toggle",
            description: "Add a toggle in settings",
            tasks: [{ title: "Build toggle component", description: "New UI component" }],
          },
        ],
      },
    ];

    it("renders the breakdown editor when candidateBreakdown is present", () => {
      renderWithProviders(
        <HumanGatePanel {...defaultProps} candidateBreakdown={candidateBreakdown} />
      );

      expect(screen.getByTestId("roadmap-candidate-breakdown")).toBeInTheDocument();
      expect(screen.getByDisplayValue("Add dark mode")).toBeInTheDocument();
      expect(screen.getByDisplayValue("Theme toggle")).toBeInTheDocument();
      expect(screen.getByDisplayValue("Build toggle component")).toBeInTheDocument();
    });

    it("does not render the breakdown editor when candidateBreakdown is null", () => {
      renderWithProviders(
        <HumanGatePanel {...defaultProps} candidateBreakdown={null} />
      );

      expect(screen.queryByTestId("roadmap-candidate-breakdown")).not.toBeInTheDocument();
    });

    it("does not render the breakdown editor when candidateBreakdown is absent (no visible change)", () => {
      renderWithProviders(<HumanGatePanel {...defaultProps} />);

      expect(screen.queryByTestId("roadmap-candidate-breakdown")).not.toBeInTheDocument();
    });

    it("includes editedCandidates in the signal payload on Approve, reflecting edits", async () => {
      const user = userEvent.setup();
      renderWithProviders(
        <HumanGatePanel {...defaultProps} candidateBreakdown={candidateBreakdown} />
      );

      const titleInput = screen.getByDisplayValue("Add dark mode");
      await user.clear(titleInput);
      await user.type(titleInput, "Add dark and light mode");

      await user.click(screen.getByText("Approve"));

      expect(mockMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          nodeExecId: "exec-1",
          decision: "approved",
          editedCandidates: [
            expect.objectContaining({ title: "Add dark and light mode" }),
          ],
        }),
        expect.objectContaining({ onSuccess: expect.any(Function) })
      );
    });

    it("does not include editedCandidates in the signal payload when candidateBreakdown was absent", async () => {
      const user = userEvent.setup();
      renderWithProviders(<HumanGatePanel {...defaultProps} />);

      await user.click(screen.getByText("Approve"));

      expect(mockMutate).toHaveBeenCalledWith(
        { nodeExecId: "exec-1", decision: "approved", feedback: "", files: [] },
        expect.objectContaining({ onSuccess: expect.any(Function) })
      );
    });
  });

  describe("v23 Final Approval gate", () => {
    const finalApprovalProps = {
      ...defaultProps,
      nodeLabel: "final_approval",
      decisionOptions: ["approved", "rereview"],
    };

    it("renders Approve and Re-review only — no Reject, no Redraft", () => {
      renderWithProviders(<HumanGatePanel {...finalApprovalProps} />);
      expect(screen.getByText("Approve")).toBeInTheDocument();
      expect(screen.getByText("Re-review")).toBeInTheDocument();
      expect(screen.queryByText("Redraft")).not.toBeInTheDocument();
      expect(screen.queryByText("Reject")).not.toBeInTheDocument();
    });

    it("submits 'rereview' on Re-review click with guidance attached", async () => {
      const user = userEvent.setup();
      renderWithProviders(<HumanGatePanel {...finalApprovalProps} />);
      const textarea = screen.getByPlaceholderText("Provide feedback for the AI agent...");
      await user.type(textarea, "Re-review the auth flow");
      await user.click(screen.getByText("Re-review"));
      expect(mockMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          decision: "rereview",
          files: expect.arrayContaining([
            expect.objectContaining({ name: "human_guidance.md" }),
          ]),
        }),
        expect.any(Object),
      );
    });
  });
});
