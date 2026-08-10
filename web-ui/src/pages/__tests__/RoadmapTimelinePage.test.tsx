import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapTimelinePage from "@/pages/RoadmapTimelinePage";
import RoadmapPage from "@/pages/RoadmapPage";
import type { RoadmapTimelineResponse } from "@/lib/types";

// STOMP client double — mirrors useRoadmapSubscription.test.ts's MockClient so the STOMP-driven
// refetch case below can simulate an inbound `roadmap-items` message end to end, through the
// *real* useRoadmapSubscription/useRoadmapTimeline hooks (neither is mocked in this file).
const mockSubscribe = vi.fn();
const mockActivate = vi.fn();
const mockDeactivate = vi.fn();

vi.mock("@stomp/stompjs", () => {
  class MockClient {
    subscribe = mockSubscribe;
    activate: () => void;
    deactivate = mockDeactivate;
    private onConnect?: () => void;

    constructor(opts: { onConnect?: () => void }) {
      this.onConnect = opts.onConnect;
      this.activate = mockActivate.mockImplementation(() => {
        if (this.onConnect) this.onConnect();
      });
    }
  }
  return { Client: MockClient };
});

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

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
};

// Real ReactFlow rendering is irrelevant to what this file tests (the page's data fetching,
// empty-state branching, and STOMP-driven refetch) — same rationale RoadmapGraphPage.test.tsx
// gives for stubbing out RoadmapGraph. Render each Epic lane / Story marker as plain text so
// assertions can query by content instead of canvas internals.
vi.mock("@/components/roadmap/RoadmapTimeline", () => ({
  default: ({ data }: { data: RoadmapTimelineResponse }) => (
    <div data-testid="mock-roadmap-timeline">
      {data.epics.map((epic) => (
        <div key={epic.id} data-testid="mock-timeline-lane">
          {epic.title}
          {epic.stories.map((story) => (
            <span key={story.id} data-testid="mock-timeline-marker">
              {story.title}
            </span>
          ))}
        </div>
      ))}
    </div>
  ),
}));

// RoadmapPage's own data hook, mocked the same way RoadmapPage.test.tsx mocks it — this file
// only needs the Timeline nav link's href, not epic-list behavior.
const mockUseEpics = vi.fn();
vi.mock("@/hooks/useEpics", () => ({
  useEpics: (...args: unknown[]) => mockUseEpics(...args),
  useCreateEpic: () => ({ mutate: vi.fn(), isPending: false, isError: false, reset: vi.fn() }),
}));
vi.mock("@/hooks/useSoftwareProjects", () => ({
  useSoftwareProjects: () => ({ data: [] }),
}));

function makeResponse(): RoadmapTimelineResponse {
  return {
    epics: [
      {
        id: "epic-1",
        title: "Add dark mode",
        stage: "in_progress",
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
        stories: [
          {
            id: "story-1",
            epicId: "epic-1",
            title: "Dark theme toggle",
            stage: "backlog",
            createdAt: "2026-04-01T00:00:00Z",
            updatedAt: "2026-04-01T00:00:00Z",
          },
        ],
      },
    ],
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockApi.getPage.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0 });
  mockUseEpics.mockReturnValue({
    data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true },
    isLoading: false,
  });
});

describe("RoadmapTimelinePage", () => {
  it("renders a lane and marker for each Epic/Story returned by the timeline fetch", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />);

    await waitFor(() => expect(screen.getByTestId("mock-roadmap-timeline")).toBeInTheDocument());
    expect(screen.getAllByTestId("mock-timeline-lane")).toHaveLength(1);
    expect(screen.getByText("Add dark mode")).toBeInTheDocument();
    expect(screen.getByText("Dark theme toggle")).toBeInTheDocument();
  });

  it("renders the dedicated zero-Epics empty state instead of a blank canvas", async () => {
    mockApi.get.mockResolvedValue({ epics: [] });

    renderWithProviders(<RoadmapTimelinePage />);

    await waitFor(() => expect(screen.getByTestId("roadmap-timeline-empty")).toBeInTheDocument());
    expect(screen.queryByTestId("mock-roadmap-timeline")).not.toBeInTheDocument();
  });

  it("a roadmap-items STOMP event triggers a refetch of the timeline query", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />);
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledTimes(1));

    const subscribeCallback = mockSubscribe.mock.calls[0][1];
    subscribeCallback({
      body: JSON.stringify({ itemType: "epic_changed", itemId: "epic-1", status: "in_progress" }),
    });

    await waitFor(() => expect(mockApi.get).toHaveBeenCalledTimes(2));
    expect(mockApi.get).toHaveBeenCalledWith("/roadmap/timeline");
  });

  it("RoadmapPage's Timeline nav link points at /roadmap/timeline", () => {
    renderWithProviders(<RoadmapPage />);

    expect(screen.getByTestId("roadmap-timeline-view-link")).toHaveAttribute("href", "/roadmap/timeline");
  });
});
