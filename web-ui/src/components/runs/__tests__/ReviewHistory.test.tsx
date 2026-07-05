import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ReviewHistory from "../ReviewHistory";

vi.mock("@/hooks/useRuns", () => ({
  useReviewHistory: vi.fn(),
}));

import { useReviewHistory } from "@/hooks/useRuns";

const mockUseReviewHistory = useReviewHistory as ReturnType<typeof vi.fn>;

/** Helper to create a review entry with sensible defaults. */
function makeReview(overrides: Record<string, unknown> = {}) {
  return {
    id: "r1",
    loopGroup: "lg-1",
    iteration: 1,
    reviewerType: "human",
    decision: "approved",
    result: null,
    status: "completed",
    artifactRefs: "",
    nodeLabel: null,
    timestamp: "2026-03-01T10:30:00Z",
    ...overrides,
  };
}

describe("ReviewHistory", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns null when loopGroup is null", () => {
    mockUseReviewHistory.mockReturnValue({ data: undefined, isLoading: false });
    const { container } = renderWithProviders(
      <ReviewHistory runId="run-1" loopGroup={null} />
    );

    expect(container.innerHTML).toBe("");
  });

  it("shows loading state", () => {
    mockUseReviewHistory.mockReturnValue({ data: undefined, isLoading: true });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    expect(screen.getByText("Loading review history...")).toBeInTheDocument();
  });

  it("shows empty state when no reviews exist", () => {
    mockUseReviewHistory.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    expect(screen.getByText("No prior reviews for this loop group.")).toBeInTheDocument();
  });

  it("renders review entries with iteration, node label, decision, and timestamp", () => {
    const history = [
      makeReview({
        id: "r1",
        iteration: 1,
        decision: "rejected",
        nodeLabel: "human_review",
        timestamp: "2026-03-01T10:30:00Z",
      }),
      makeReview({
        id: "r2",
        iteration: 2,
        decision: "approved",
        nodeLabel: "human_review",
        timestamp: "2026-03-01T11:00:00Z",
      }),
    ];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    expect(screen.getByText("Review History (2)")).toBeInTheDocument();
    expect(screen.getByText("Iteration 1")).toBeInTheDocument();
    expect(screen.getByText("Iteration 2")).toBeInTheDocument();
    expect(screen.getByText("rejected")).toBeInTheDocument();
    expect(screen.getByText("approved")).toBeInTheDocument();
    // Node label badges should appear for both entries
    expect(screen.getAllByText("human_review")).toHaveLength(2);
  });

  it("toggles expanded/collapsed state", async () => {
    const user = userEvent.setup();
    const history = [makeReview()];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    // Initially expanded — iteration should be visible
    expect(screen.getByText("Iteration 1")).toBeInTheDocument();

    // Click to collapse
    await user.click(screen.getByText("Review History (1)"));
    expect(screen.queryByText("Iteration 1")).not.toBeInTheDocument();

    // Click to expand again
    await user.click(screen.getByText("Review History (1)"));
    expect(screen.getByText("Iteration 1")).toBeInTheDocument();
  });

  it("uses warning badge classes for rejected decisions", () => {
    const history = [makeReview({ decision: "rejected" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    const badge = screen.getByText("rejected");
    expect(badge.className).toContain("bg-status-warning/15");
    expect(badge.className).toContain("text-status-warning");
  });

  it("uses success badge classes for approved decisions", () => {
    const history = [makeReview({ decision: "approved" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    const badge = screen.getByText("approved");
    expect(badge.className).toContain("bg-status-success/15");
    expect(badge.className).toContain("text-status-success");
  });

  it("does not show decision badge for no_decision", () => {
    const history = [makeReview({ decision: "no_decision", nodeLabel: "ai_draft" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    // Node label should appear, but no_decision should not
    expect(screen.getByText("ai_draft")).toBeInTheDocument();
    expect(screen.queryByText("no_decision")).not.toBeInTheDocument();
  });

  it("shows node label badge separately from decision badge", () => {
    const history = [makeReview({ decision: "approved", nodeLabel: "human_review_spec" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    const nodeLabelBadge = screen.getByText("human_review_spec");
    const decisionBadge = screen.getByText("approved");

    // They should be separate elements
    expect(nodeLabelBadge).not.toBe(decisionBadge);
    // Node label is always neutral
    expect(nodeLabelBadge.className).toContain("bg-status-neutral/15");
    // Decision badge uses success for approved
    expect(decisionBadge.className).toContain("bg-status-success/15");
  });

  it("falls back to reviewerType when nodeLabel is null", () => {
    const history = [makeReview({ nodeLabel: null, reviewerType: "human" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    expect(screen.getByText("human")).toBeInTheDocument();
  });

  it("displays truncated content from result field", () => {
    const history = [makeReview({ result: "Some review content here" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    expect(screen.getByText("Some review content here")).toBeInTheDocument();
  });

  it("shows 'View full review' button for long content", () => {
    const longContent = "A".repeat(200);
    const history = [makeReview({ result: longContent })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    expect(screen.getByText("View full review")).toBeInTheDocument();
  });

  it("does not show 'View full review' for short content", () => {
    const history = [makeReview({ result: "Short" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    expect(screen.queryByText("View full review")).not.toBeInTheDocument();
  });

  it("does not show content preview when result is null", () => {
    const history = [makeReview({ result: null })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    // Should only show metadata row, not any paragraph content
    expect(screen.queryByText("View full review")).not.toBeInTheDocument();
  });

  it("colors timeline dot green for completed status", () => {
    const history = [makeReview({ status: "completed" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    const iterationSpan = screen.getByText("Iteration 1");
    const entryDiv = iterationSpan.closest(".relative.mb-3");
    const dot = entryDiv?.querySelector(".rounded-full");
    expect(dot?.className).toContain("bg-status-success");
  });

  it("colors timeline dot with status-error for failed status", () => {
    const history = [makeReview({ status: "failed" })];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    const iterationSpan = screen.getByText("Iteration 1");
    const entryDiv = iterationSpan.closest(".relative.mb-3");
    const dot = entryDiv?.querySelector(".rounded-full");
    expect(dot?.className).toContain("bg-status-error");
  });

  it("applies flex-wrap on the metadata row", () => {
    const history = [makeReview()];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    const iterationSpan = screen.getByText("Iteration 1");
    const metaRow = iterationSpan.parentElement;
    expect(metaRow?.className).toContain("flex-wrap");
  });

  it("applies overflow-x-hidden on the timeline container", () => {
    const history = [makeReview()];
    mockUseReviewHistory.mockReturnValue({ data: history, isLoading: false });
    renderWithProviders(<ReviewHistory runId="run-1" loopGroup="lg-1" />);

    const iterationSpan = screen.getByText("Iteration 1");
    const timelineContainer = iterationSpan.closest(".border-l");
    expect(timelineContainer?.className).toContain("overflow-x-hidden");
  });
});
