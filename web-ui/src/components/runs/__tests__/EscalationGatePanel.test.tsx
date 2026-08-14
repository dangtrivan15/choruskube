import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EscalationGatePanel from "../EscalationGatePanel";
import type { EscalationContext } from "@/lib/types";

vi.mock("@/hooks/useRuns", () => ({
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
}));

vi.mock("../ArtifactList", () => ({
  default: ({ groups }: { runId: string; groups: unknown[] }) => (
    <div data-testid="artifact-list-mock">ArtifactList ({groups.length} groups)</div>
  ),
}));

import { useReviewHistory } from "@/hooks/useRuns";
import { useArtifacts } from "@/hooks/useArtifacts";

const mockUseReviewHistory = useReviewHistory as ReturnType<typeof vi.fn>;
const mockUseArtifacts = useArtifacts as ReturnType<typeof vi.fn>;

const fullEscalation: EscalationContext = {
  escalatorLabel: "Code Review",
  escalatorExecId: "escalator-exec-1",
  escalatorLoopGroup: "loop-a",
  category: "blocked_external",
  summary: "CI runner is wedged and cannot be reached.",
};

/**
 * What ArtifactResolutionService.resolveRequiredArtifacts synthesises for every
 * Supervisor gate: the escalator's escalation.md, flagged required — the one
 * document the reviewer must read before routing.
 */
const requiredArtifacts = [
  {
    nodeExecutionId: "escalator-exec-1",
    nodeLabel: "Code Review",
    artifacts: [
      { name: "escalation.md", description: "Why this run was escalated to the Supervisor", required: true },
    ],
  },
];

const routeOptions = ["route:qa_review", "route:implement", "route:final_approval"];

function noop() {}

describe("EscalationGatePanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseReviewHistory.mockReturnValue({ data: [], isLoading: false });
    mockUseArtifacts.mockReturnValue({ data: [], isLoading: false });
  });

  it("renders the category banner for the escalation's category", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.getByText("Blocked on an external dependency")).toBeInTheDocument();
  });

  it("renders the escalator's label and summary", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.getByText("Code Review")).toBeInTheDocument();
    expect(screen.getByText("CI runner is wedged and cannot be reached.")).toBeInTheDocument();
  });

  // Regression coverage: ArtifactResolutionService synthesises exactly one required-artifact
  // group for every Supervisor gate — the escalating node's escalation.md, flagged required.
  // That's the one document the reviewer must read before routing; dropping it degrades the
  // panel from "here is the required document" to "one filename in the escalator's file list,
  // unmarked".
  it("renders the required-artifact list (the escalator's escalation.md) when provided", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        requiredArtifacts={requiredArtifacts}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.getByTestId("artifact-list-mock")).toBeInTheDocument();
    expect(screen.getByText("ArtifactList (1 groups)")).toBeInTheDocument();
  });

  it("does not render the required-artifact list when requiredArtifacts is null", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        requiredArtifacts={null}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.queryByTestId("artifact-list-mock")).not.toBeInTheDocument();
  });

  it("does not render the required-artifact list when requiredArtifacts is an empty array", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        requiredArtifacts={[]}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.queryByTestId("artifact-list-mock")).not.toBeInTheDocument();
  });

  it("still renders the escalator's full ArtifactBrowser alongside the required-artifact list", () => {
    // The two are complementary: ArtifactList is "what you must read" (escalation.md),
    // ArtifactBrowser is "everything the escalator produced". Neither replaces the other.
    mockUseArtifacts.mockReturnValue({
      data: [{ name: "escalation.md", size: 128, lastModified: "2026-01-01T00:00:00Z" }],
      isLoading: false,
    });

    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        requiredArtifacts={requiredArtifacts}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.getByTestId("artifact-list-mock")).toBeInTheDocument();
    expect(screen.getByTestId("artifact-browser")).toBeInTheDocument();
  });

  it("scopes ReviewHistory to the escalator's loop group, not the Supervisor's", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(mockUseReviewHistory).toHaveBeenCalledWith("run-1", "loop-a");
  });

  it("fetches the escalator's artifacts via its exec id", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(mockUseArtifacts).toHaveBeenCalledWith("run-1", "escalator-exec-1");
  });

  it("degrades gracefully when escalation is null: still renders a functional picker", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={null}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.getByTestId("escalation-target-picker")).toBeInTheDocument();
    expect(screen.getByTestId("escalation-confirm-button")).toBeInTheDocument();
  });

  it("does not gate the picker or confirm button on category/summary being null", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const nullCategoryEscalation: EscalationContext = {
      escalatorLabel: "Code Review",
      escalatorExecId: "escalator-exec-1",
      escalatorLoopGroup: "loop-a",
      category: null,
      summary: null,
    };

    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={nullCategoryEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={onConfirm}
        isPending={false}
      />
    );

    await user.click(screen.getByTestId("escalation-target-picker"));
    await user.click(screen.getByTestId("escalation-target-option-qa_review"));
    expect(screen.getByTestId("escalation-confirm-button")).not.toBeDisabled();

    await user.click(screen.getByTestId("escalation-confirm-button"));
    expect(onConfirm).toHaveBeenCalledWith("route:qa_review");
  });

  it("disables the confirm button until a target is chosen", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
      />
    );

    expect(screen.getByTestId("escalation-confirm-button")).toBeDisabled();
  });

  it("calls onConfirm with the chosen route: target when confirmed", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();

    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={onConfirm}
        isPending={false}
      />
    );

    await user.click(screen.getByTestId("escalation-target-picker"));
    await user.click(screen.getByTestId("escalation-target-option-implement"));
    await user.click(screen.getByTestId("escalation-confirm-button"));

    expect(onConfirm).toHaveBeenCalledWith("route:implement");
  });

  it("calls onGuidanceChange as the guidance textarea is typed", async () => {
    const user = userEvent.setup();
    const onGuidanceChange = vi.fn();

    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={onGuidanceChange}
        onConfirm={noop}
        isPending={false}
      />
    );

    const textarea = screen.getByTestId("escalation-guidance-input");
    await user.type(textarea, "x");
    expect(onGuidanceChange).toHaveBeenCalled();
  });

  it("hides guidance, picker, and confirm when readOnly, but keeps the informational content", () => {
    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={false}
        readOnly
      />
    );

    expect(screen.queryByTestId("escalation-guidance-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("escalation-target-picker")).not.toBeInTheDocument();
    expect(screen.queryByTestId("escalation-confirm-button")).not.toBeInTheDocument();
    // Informational content still renders for read-only viewers
    expect(screen.getByText("Code Review")).toBeInTheDocument();
  });

  it("disables the confirm button while a signal is pending", async () => {
    const user = userEvent.setup();

    renderWithProviders(
      <EscalationGatePanel
        runId="run-1"
        escalation={fullEscalation}
        decisionOptions={routeOptions}
        guidance=""
        onGuidanceChange={noop}
        onConfirm={noop}
        isPending={true}
      />
    );

    await user.click(screen.getByTestId("escalation-target-picker"));
    await user.click(screen.getByTestId("escalation-target-option-implement"));

    expect(screen.getByTestId("escalation-confirm-button")).toBeDisabled();
  });
});
