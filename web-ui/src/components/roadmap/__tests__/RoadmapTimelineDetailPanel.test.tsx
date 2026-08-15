import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapTimelineDetailPanel from "../RoadmapTimelineDetailPanel";
import type { TimelineEpicSummary, TimelineStorySummary } from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

import { api } from "@/lib/api";

const mockApi = api as unknown as { get: ReturnType<typeof vi.fn> };

beforeEach(() => {
  vi.clearAllMocks();
});

function makeStory(overrides: Partial<TimelineStorySummary> = {}): TimelineStorySummary {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Add refund flow",
    stage: "in_progress",
    priority: "medium",
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readiness: "READY",
    stalled: false,
    ...overrides,
  };
}

function makeEpic(overrides: Partial<TimelineEpicSummary> = {}): TimelineEpicSummary {
  return {
    id: "epic-1",
    title: "Payments Overhaul",
    stage: "in_progress",
    priority: "medium",
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    stories: [],
    stalled: false,
    milestone: null,
    ...overrides,
  };
}

describe("RoadmapTimelineDetailPanel", () => {
  it("renders a Story's title, stage, parent Epic link, and calls onClose", async () => {
    const onClose = vi.fn();
    const epic = makeEpic();
    const story = makeStory();
    renderWithProviders(<RoadmapTimelineDetailPanel epic={epic} story={story} onClose={onClose} />);

    expect(screen.getByTestId("roadmap-timeline-detail-panel")).toBeInTheDocument();
    expect(screen.getByTestId("roadmap-timeline-detail-title")).toHaveTextContent("Add refund flow");
    expect(screen.getByTestId("roadmap-timeline-detail-parent")).toHaveTextContent("Payments Overhaul");

    const user = userEvent.setup();
    await user.click(screen.getByTestId("roadmap-timeline-detail-close"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("fetches and renders the blocking chain for a BLOCKED Story", async () => {
    const epic = makeEpic();
    const story = makeStory({ readiness: "BLOCKED" });
    mockApi.get.mockResolvedValue({
      itemType: "story",
      itemId: story.id,
      title: story.title,
      status: "in_progress",
      readiness: "BLOCKED",
      blockedBy: [
        { itemType: "story", itemId: "story-2", title: "Upstream blocker", status: "in_progress", blockedBy: [] },
      ],
      truncated: false,
    });

    renderWithProviders(<RoadmapTimelineDetailPanel epic={epic} story={story} onClose={vi.fn()} />);

    expect(await screen.findByTestId("roadmap-blocking-chain")).toBeInTheDocument();
    expect(mockApi.get).toHaveBeenCalledWith(`/stories/${story.id}/blocking-chain`);
  });

  it("renders no blocker section and issues no fetch for a READY Story", () => {
    const epic = makeEpic();
    const story = makeStory({ readiness: "READY" });
    renderWithProviders(<RoadmapTimelineDetailPanel epic={epic} story={story} onClose={vi.fn()} />);

    expect(screen.queryByTestId("roadmap-blocking-chain")).not.toBeInTheDocument();
    expect(screen.queryByTestId("roadmap-blocking-chain-loading")).not.toBeInTheDocument();
    expect(mockApi.get).not.toHaveBeenCalled();
  });

  it("renders the Epic story rollup and never fetches when only the Epic is focused", () => {
    const epic = makeEpic({
      stories: [
        makeStory({ id: "s1", readiness: "BLOCKED", stalled: false }),
        makeStory({ id: "s2", readiness: "READY", stalled: true }),
        makeStory({ id: "s3", readiness: "READY", stalled: false }),
      ],
    });
    renderWithProviders(<RoadmapTimelineDetailPanel epic={epic} onClose={vi.fn()} />);

    expect(screen.getByTestId("roadmap-timeline-detail-title")).toHaveTextContent("Payments Overhaul");
    expect(screen.queryByTestId("roadmap-timeline-detail-parent")).not.toBeInTheDocument();
    const rollup = screen.getByTestId("roadmap-timeline-detail-rollup");
    expect(rollup).toHaveTextContent("3 Stories");
    expect(rollup).toHaveTextContent("1 blocked, 1 stalled");
    expect(mockApi.get).not.toHaveBeenCalled();
  });

  it("surfaces the truncated note when the blocking chain response is truncated", async () => {
    const epic = makeEpic();
    const story = makeStory({ readiness: "BLOCKED" });
    mockApi.get.mockResolvedValue({
      itemType: "story",
      itemId: story.id,
      title: story.title,
      status: "in_progress",
      readiness: "BLOCKED",
      blockedBy: [
        { itemType: "story", itemId: "story-2", title: "Upstream blocker", status: "in_progress", blockedBy: [] },
      ],
      truncated: true,
    });

    renderWithProviders(<RoadmapTimelineDetailPanel epic={epic} story={story} onClose={vi.fn()} />);

    expect(await screen.findByTestId("roadmap-blocking-chain-truncated")).toBeInTheDocument();
  });
});
