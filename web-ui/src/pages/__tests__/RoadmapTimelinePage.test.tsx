import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapTimelinePage from "@/pages/RoadmapTimelinePage";
import RoadmapPage from "@/pages/RoadmapPage";
import type { RoadmapTimelineResponse } from "@/lib/types";

// Spies on every `useSearchParams()` call in the rendered tree (RoadmapTimelinePage's own call —
// this file doesn't mock react-router's other exports, so `MemoryRouter`/`Link`/etc. still behave
// normally). `latestSearch` captures the *raw* `URLSearchParams#toString()` output on every
// render, so a regression that writes the literal string "story=undefined" into the URL (see
// roadmapFocus.ts's focusToSearchParamsInit doc comment) is caught by asserting against the
// string itself, not just the parsed `parseFocusParams` result.
const searchParamsSpy = vi.hoisted(() => ({
  calls: [] as Array<{ init: unknown; options: unknown }>,
  latestSearch: "",
}));
vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useSearchParams: (...args: Parameters<typeof actual.useSearchParams>) => {
      const [params, setParams] = actual.useSearchParams(...args);
      searchParamsSpy.latestSearch = params.toString();
      const spiedSetParams: typeof setParams = (init, options) => {
        searchParamsSpy.calls.push({ init, options });
        setParams(init, options);
      };
      return [params, spiedSetParams];
    },
  };
});

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
// empty-state branching, focus wiring, and STOMP-driven refetch) — same rationale
// RoadmapGraphPage.test.tsx gives for stubbing out RoadmapGraph. Render each Epic lane / Story
// marker as a clickable element so assertions can query by content and simulate a lane/marker
// click via `onFocusChange`, the same way RoadmapGraphPage.test.tsx's mock RoadmapGraph exposes a
// `mock-select-<id>` button per node.
vi.mock("@/components/roadmap/RoadmapTimeline", () => ({
  default: ({
    data,
    focusedEpicId,
    focusedStoryId,
    onFocusChange,
  }: {
    data: RoadmapTimelineResponse;
    focusedEpicId?: string;
    focusedStoryId?: string;
    onFocusChange?: (epicId: string, storyId?: string) => void;
  }) => (
    <div
      data-testid="mock-roadmap-timeline"
      data-focused-epic={focusedEpicId ?? ""}
      data-focused-story={focusedStoryId ?? ""}
    >
      {data.epics.map((epic) => (
        <div key={epic.id} data-testid="mock-timeline-lane" data-stalled={String(epic.stalled)}>
          <button data-testid={`mock-focus-lane-${epic.id}`} onClick={() => onFocusChange?.(epic.id)}>
            {epic.title}
          </button>
          {epic.stories.map((story) => (
            <button
              key={story.id}
              data-testid={`mock-focus-story-${story.id}`}
              data-readiness={story.readiness}
              data-stalled={String(story.stalled)}
              onClick={() => onFocusChange?.(epic.id, story.id)}
            >
              {story.title}
            </button>
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

// Defaults to desktop (mirrors RoadmapGraphPage.test.tsx) — individual tests override via
// mockUseMobileBreakpoint.mockReturnValue(true) to exercise the mobile bottom-sheet path.
const mockUseMobileBreakpoint = vi.fn(() => false);
vi.mock("@/hooks/useMobileBreakpoint", () => ({
  useMobileBreakpoint: () => mockUseMobileBreakpoint(),
}));

function makeResponse(overrides: { readiness?: "READY" | "BLOCKED"; storyStalled?: boolean; epicStalled?: boolean } = {}): RoadmapTimelineResponse {
  return {
    epics: [
      {
        id: "epic-1",
        title: "Add dark mode",
        stage: "in_progress",
        priority: "medium",
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
        stalled: overrides.epicStalled ?? false,
        milestone: null,
        stories: [
          {
            id: "story-1",
            epicId: "epic-1",
            title: "Dark theme toggle",
            stage: "backlog",
            priority: "medium",
            createdAt: "2026-04-01T00:00:00Z",
            updatedAt: "2026-04-01T00:00:00Z",
            readiness: overrides.readiness ?? "READY",
            stalled: overrides.storyStalled ?? false,
          },
        ],
      },
    ],
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  searchParamsSpy.calls = [];
  searchParamsSpy.latestSearch = "";
  mockApi.getPage.mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0 });
  mockUseEpics.mockReturnValue({
    data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true },
    isLoading: false,
  });
  mockUseMobileBreakpoint.mockReturnValue(false);
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

  it("propagates readiness and stalled fields from the fetched response through to the rendered nodes", async () => {
    mockApi.get.mockResolvedValue(makeResponse({ readiness: "BLOCKED", storyStalled: true, epicStalled: true }));

    renderWithProviders(<RoadmapTimelinePage />);

    await waitFor(() => expect(screen.getByTestId("mock-focus-story-story-1")).toBeInTheDocument());
    expect(screen.getByTestId("mock-focus-story-story-1")).toHaveAttribute("data-readiness", "BLOCKED");
    expect(screen.getByTestId("mock-focus-story-story-1")).toHaveAttribute("data-stalled", "true");
    expect(screen.getByTestId("mock-timeline-lane")).toHaveAttribute("data-stalled", "true");
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

  it("RoadmapPage's Timeline view button points at /roadmap/timeline", () => {
    renderWithProviders(<RoadmapPage />);

    expect(screen.getByTestId("roadmap-view-timeline")).toHaveAttribute("href", "/roadmap/timeline");
  });

  // --- Focus / RoadmapViewControls wiring ---

  it("reads the epic query param and passes it to RoadmapTimeline as focusedEpicId", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />, { initialEntries: ["/roadmap/timeline?epic=epic-1"] });

    await waitFor(() => expect(screen.getByTestId("mock-roadmap-timeline")).toBeInTheDocument());
    expect(screen.getByTestId("mock-roadmap-timeline")).toHaveAttribute("data-focused-epic", "epic-1");
  });

  it("clicking a Story marker updates the URL via history replace, not push", async () => {
    mockApi.get.mockResolvedValue(makeResponse());
    const user = userEvent.setup();

    renderWithProviders(<RoadmapTimelinePage />);
    await waitFor(() => expect(screen.getByTestId("mock-focus-story-story-1")).toBeInTheDocument());

    await user.click(screen.getByTestId("mock-focus-story-story-1"));

    await waitFor(() => expect(searchParamsSpy.latestSearch).toBe("epic=epic-1&story=story-1"));
    expect(searchParamsSpy.calls[searchParamsSpy.calls.length - 1]?.options).toEqual({ replace: true });
  });

  it("clicking an Epic lane (no Story) writes exactly ?epic=..., with no story key at all", async () => {
    mockApi.get.mockResolvedValue(makeResponse());
    const user = userEvent.setup();

    renderWithProviders(<RoadmapTimelinePage />, { initialEntries: ["/roadmap/timeline?epic=epic-1&story=story-1"] });
    await waitFor(() => expect(screen.getByTestId("mock-focus-lane-epic-1")).toBeInTheDocument());

    await user.click(screen.getByTestId("mock-focus-lane-epic-1"));

    // Asserts the literal search string, not just the parsed value — a regression that writes
    // `story=undefined` instead of dropping the key would still parse to `{ epicId: "epic-1" }`.
    await waitFor(() => expect(searchParamsSpy.latestSearch).toBe("epic=epic-1"));
    expect(searchParamsSpy.latestSearch).not.toContain("story");
  });

  it("with no query params, the header's Graph action is disabled", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />);

    await waitFor(() => expect(screen.getByTestId("mock-roadmap-timeline")).toBeInTheDocument());
    expect(screen.getByTestId("roadmap-graph-action")).toBeDisabled();
  });

  it("an epic/story param matching no loaded Epic/Story renders exactly like the no-focus case — no throw, no blank canvas, Graph stays disabled", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />, {
      initialEntries: ["/roadmap/timeline?epic=does-not-exist&story=does-not-exist-either"],
    });

    await waitFor(() => expect(screen.getByTestId("mock-roadmap-timeline")).toBeInTheDocument());
    expect(screen.getByText("Add dark mode")).toBeInTheDocument();
    expect(screen.getByTestId("mock-roadmap-timeline")).toHaveAttribute("data-focused-epic", "");
    expect(screen.getByTestId("mock-roadmap-timeline")).toHaveAttribute("data-focused-story", "");
    expect(screen.getByTestId("roadmap-graph-action")).toBeDisabled();
  });

  // --- Detail panel (item-detail hover/click) ---

  it("renders no detail panel when nothing is focused", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />);

    await waitFor(() => expect(screen.getByTestId("mock-roadmap-timeline")).toBeInTheDocument());
    expect(screen.queryByTestId("roadmap-timeline-detail-panel")).not.toBeInTheDocument();
  });

  it("focusing a Story renders the detail panel with its title and parent Epic", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />, { initialEntries: ["/roadmap/timeline?epic=epic-1&story=story-1"] });

    await waitFor(() => expect(screen.getByTestId("roadmap-timeline-detail-panel")).toBeInTheDocument());
    expect(screen.getByTestId("roadmap-timeline-detail-title")).toHaveTextContent("Dark theme toggle");
    expect(screen.getByTestId("roadmap-timeline-detail-parent")).toHaveTextContent("Add dark mode");
  });

  it("focusing only an Epic renders the detail panel without a parent line", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />, { initialEntries: ["/roadmap/timeline?epic=epic-1"] });

    await waitFor(() => expect(screen.getByTestId("roadmap-timeline-detail-panel")).toBeInTheDocument());
    expect(screen.getByTestId("roadmap-timeline-detail-title")).toHaveTextContent("Add dark mode");
    expect(screen.queryByTestId("roadmap-timeline-detail-parent")).not.toBeInTheDocument();
  });

  it("closing the panel clears the focus search params via history replace", async () => {
    mockApi.get.mockResolvedValue(makeResponse());
    const user = userEvent.setup();

    renderWithProviders(<RoadmapTimelinePage />, { initialEntries: ["/roadmap/timeline?epic=epic-1&story=story-1"] });
    await waitFor(() => expect(screen.getByTestId("roadmap-timeline-detail-panel")).toBeInTheDocument());

    await user.click(screen.getByTestId("roadmap-timeline-detail-close"));

    await waitFor(() => expect(screen.queryByTestId("roadmap-timeline-detail-panel")).not.toBeInTheDocument());
    const lastCall = searchParamsSpy.calls[searchParamsSpy.calls.length - 1];
    expect(lastCall?.options).toEqual({ replace: true });
  });

  it("an unresolved focused id renders no panel, consistent with the no-focus case", async () => {
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />, {
      initialEntries: ["/roadmap/timeline?epic=does-not-exist&story=does-not-exist-either"],
    });

    await waitFor(() => expect(screen.getByTestId("mock-roadmap-timeline")).toBeInTheDocument());
    expect(screen.queryByTestId("roadmap-timeline-detail-panel")).not.toBeInTheDocument();
  });

  it("uses the mobile bottom-sheet overlay testid when the mobile breakpoint is active", async () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    mockApi.get.mockResolvedValue(makeResponse());

    renderWithProviders(<RoadmapTimelinePage />, { initialEntries: ["/roadmap/timeline?epic=epic-1&story=story-1"] });

    await waitFor(() => expect(screen.getByTestId("roadmap-timeline-mobile-detail-overlay")).toBeInTheDocument());
    expect(
      screen.getByTestId("roadmap-timeline-mobile-detail-overlay").querySelector('[data-testid="roadmap-timeline-detail-panel"]'),
    ).not.toBeNull();
  });
});
