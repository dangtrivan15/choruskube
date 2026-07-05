import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import ReviewDetailDialog, {
  decisionBadgeClass,
} from "../ReviewDetailDialog";
import type { ReviewHistoryResponse } from "@/lib/types";

const baseReview: ReviewHistoryResponse = {
  id: "r1",
  loopGroup: "lg-1",
  iteration: 3,
  reviewerType: "human",
  decision: "rejected",
  result: "The implementation needs more error handling around the API calls.",
  status: "completed",
  artifactRefs: "",
  nodeLabel: "human_review_spec",
  timestamp: "2026-03-15T14:30:00Z",
};

describe("ReviewDetailDialog", () => {
  it("does not render when review is null", () => {
    const { container } = renderWithProviders(
      <ReviewDetailDialog review={null} open={false} onOpenChange={() => {}} />
    );
    expect(container.innerHTML).toBe("");
  });

  it("does not render visible content when open is false", () => {
    renderWithProviders(
      <ReviewDetailDialog
        review={baseReview}
        open={false}
        onOpenChange={() => {}}
      />
    );
    expect(screen.queryByText(/Iteration 3/)).not.toBeInTheDocument();
  });

  it("renders the iteration number in the title", () => {
    renderWithProviders(
      <ReviewDetailDialog
        review={baseReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    const title = screen.getByRole("heading", { level: 2 });
    expect(title).toHaveTextContent("Review — Iteration 3");
  });

  it("renders the node label badge", () => {
    renderWithProviders(
      <ReviewDetailDialog
        review={baseReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByText("human_review_spec")).toBeInTheDocument();
  });

  it("renders the decision badge with correct text", () => {
    renderWithProviders(
      <ReviewDetailDialog
        review={baseReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByText("rejected")).toBeInTheDocument();
  });

  it("renders formatted timestamp in the description", () => {
    renderWithProviders(
      <ReviewDetailDialog
        review={baseReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByText(/Mar 15, 2026/)).toBeInTheDocument();
  });

  it("renders result content via MarkdownViewer", () => {
    renderWithProviders(
      <ReviewDetailDialog
        review={baseReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(
      screen.getByText(/implementation needs more error handling/)
    ).toBeInTheDocument();
  });

  it("shows empty state when result is null", () => {
    const noResultReview: ReviewHistoryResponse = {
      ...baseReview,
      result: null,
    };
    renderWithProviders(
      <ReviewDetailDialog
        review={noResultReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(
      screen.getByText("No content available for this review.")
    ).toBeInTheDocument();
  });

  it("hides decision badge for no_decision", () => {
    const noDecisionReview: ReviewHistoryResponse = {
      ...baseReview,
      decision: "no_decision",
    };
    renderWithProviders(
      <ReviewDetailDialog
        review={noDecisionReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.queryByText("no_decision")).not.toBeInTheDocument();
  });

  it("falls back to reviewerType when nodeLabel is null", () => {
    const noLabelReview: ReviewHistoryResponse = {
      ...baseReview,
      nodeLabel: null,
    };
    renderWithProviders(
      <ReviewDetailDialog
        review={noLabelReview}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByText("human")).toBeInTheDocument();
  });

  describe("decisionBadgeClass", () => {
    it("returns success classes for approved", () => {
      const cls = decisionBadgeClass("approved");
      expect(cls).toContain("bg-status-success/15");
      expect(cls).toContain("text-status-success");
    });

    it("returns warning classes for rejected", () => {
      const cls = decisionBadgeClass("rejected");
      expect(cls).toContain("bg-status-warning/15");
      expect(cls).toContain("text-status-warning");
    });

    it("returns neutral classes for unknown decisions", () => {
      const cls = decisionBadgeClass("some_unknown");
      expect(cls).toContain("bg-status-neutral/15");
      expect(cls).toContain("text-status-neutral");
    });
  });
});
