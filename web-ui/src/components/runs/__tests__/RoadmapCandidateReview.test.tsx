import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapCandidateReview from "@/components/runs/RoadmapCandidateReview";
import type { RoadmapCandidatesDocument } from "@/lib/types";

vi.mock("@/components/runs/RoadmapCandidateBreakdown", () => ({
  default: () => <div data-testid="mock-breakdown" />,
}));
vi.mock("@/components/runs/RoadmapCandidateGraph", () => ({
  default: () => <div data-testid="mock-graph" />,
}));

const doc: RoadmapCandidatesDocument = { milestones: [], epics: [], dependencies: [] };

describe("RoadmapCandidateReview", () => {
  it("shows the card breakdown by default, not the graph", () => {
    renderWithProviders(<RoadmapCandidateReview value={doc} onChange={vi.fn()} />);
    expect(screen.getByTestId("mock-breakdown")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-graph")).not.toBeInTheDocument();
  });

  it("switches to the graph view and back", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RoadmapCandidateReview value={doc} onChange={vi.fn()} />);

    await user.click(screen.getByTestId("candidate-view-graph"));
    expect(screen.getByTestId("mock-graph")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-breakdown")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("candidate-view-cards"));
    expect(screen.getByTestId("mock-breakdown")).toBeInTheDocument();
    expect(screen.queryByTestId("mock-graph")).not.toBeInTheDocument();
  });
});
