import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapTimelineItemPreview from "../RoadmapTimelineItemPreview";
import type { TimelineEpicLaneNodeData, TimelineStoryNodeData } from "@/lib/timelineLayout";

function makeStoryData(overrides: Partial<TimelineStoryNodeData> = {}): TimelineStoryNodeData {
  return {
    storyId: "story-1",
    epicId: "epic-1",
    epicTitle: "Payments Overhaul",
    title: "Add refund flow",
    stage: "in_progress",
    priority: "medium",
    createdAt: "2026-04-01T00:00:00Z",
    isFocused: false,
    blocked: false,
    stalled: false,
    ...overrides,
  };
}

function makeEpicData(overrides: Partial<TimelineEpicLaneNodeData> = {}): TimelineEpicLaneNodeData {
  return {
    epicId: "epic-1",
    title: "Payments Overhaul",
    stage: "in_progress",
    priority: "medium",
    isFocused: false,
    blocked: false,
    stalled: false,
    ...overrides,
  };
}

describe("RoadmapTimelineItemPreview", () => {
  it("renders a Story's title, parent Epic, and a BLOCKED readiness badge when blocked", () => {
    renderWithProviders(<RoadmapTimelineItemPreview item={makeStoryData({ blocked: true })} />);

    expect(screen.getByTestId("roadmap-timeline-item-preview")).toBeInTheDocument();
    expect(screen.getByText("Add refund flow")).toBeInTheDocument();
    expect(screen.getByText("in Payments Overhaul")).toBeInTheDocument();
    expect(screen.getByText("Blocked")).toBeInTheDocument();
  });

  it("renders no readiness badge for a ready (not blocked) Story", () => {
    renderWithProviders(<RoadmapTimelineItemPreview item={makeStoryData({ blocked: false })} />);

    expect(screen.queryByText("Blocked")).not.toBeInTheDocument();
  });

  it("renders an Epic preview with no parent Epic line and no readiness badge", () => {
    renderWithProviders(<RoadmapTimelineItemPreview item={makeEpicData({ blocked: true })} />);

    expect(screen.getByText("Payments Overhaul")).toBeInTheDocument();
    expect(screen.queryByTestId("roadmap-timeline-item-preview-parent")).not.toBeInTheDocument();
    expect(screen.queryByText("Blocked")).not.toBeInTheDocument();
  });

  it("shows the stalled badge only when the item is stalled", () => {
    const { rerender } = renderWithProviders(<RoadmapTimelineItemPreview item={makeStoryData({ stalled: true })} />);
    expect(screen.getByText("Stalled")).toBeInTheDocument();

    rerender(<RoadmapTimelineItemPreview item={makeStoryData({ stalled: false })} />);
    expect(screen.queryByText("Stalled")).not.toBeInTheDocument();
  });
});
