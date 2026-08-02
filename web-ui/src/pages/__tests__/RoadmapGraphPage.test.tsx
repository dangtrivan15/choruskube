import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapGraphPage from "@/pages/RoadmapGraphPage";
import type { EpicResponse, StoryResponse, TaskResponse, RoadmapGraphSnapshot } from "@/lib/types";
import type { RoadmapDetailItem } from "@/components/roadmap/RoadmapGraphDetailPanel";

const mockUseRoadmapGraph = vi.fn();
vi.mock("@/hooks/useRoadmapGraph", () => ({
  useRoadmapGraph: (epicId: string | undefined) => mockUseRoadmapGraph(epicId),
}));

vi.mock("@/hooks/useRoadmapSubscription", () => ({
  useRoadmapSubscription: vi.fn(),
}));

vi.mock("@/hooks/useMobileBreakpoint", () => ({
  useMobileBreakpoint: () => false,
}));

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

vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useParams: () => ({ epicId: "epic-1" }),
  };
});

// Real ReactFlow/ELK rendering needs its own dedicated mocking (see
// RoadmapGraph.test.tsx's @xyflow/react mock) that's irrelevant to what this
// file tests: RoadmapGraphPage's own selection-state logic. Replace the graph
// with a button per node that calls `onNodeSelect` the same way a real node
// click would, but keep `findDetailItem` real so the page's re-resolution of
// `selectedId` against the latest snapshot (the thing under test) is exercised
// unmocked.
vi.mock("@/components/roadmap/RoadmapGraph", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/components/roadmap/RoadmapGraph")>();
  return {
    ...actual,
    default: ({
      snapshot,
      onNodeSelect,
    }: {
      snapshot: RoadmapGraphSnapshot;
      onNodeSelect: (detail: RoadmapDetailItem | null) => void;
    }) => (
      <div data-testid="mock-roadmap-graph">
        {[snapshot.epic.id, ...snapshot.stories.map((s) => s.id), ...snapshot.tasks.map((t) => t.id)].map((id) => (
          <button
            key={id}
            data-testid={`mock-select-${id}`}
            onClick={() => onNodeSelect(actual.findDetailItem(id, snapshot))}
          >
            select {id}
          </button>
        ))}
      </div>
    ),
  };
});

beforeEach(() => {
  mockUseRoadmapGraph.mockReset();
});

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Epic description",
    motivation: null,
    status: "in_progress",
    stage: "in_progress",
    progress: { totalTasks: 1, doneTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    ...overrides,
  };
}

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "desc",
    status: "in_progress",
    readiness: null,
    progress: { totalTasks: 1, doneTasks: 0 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Wire up the toggle",
    description: "desc",
    status: "backlog",
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    latestRunId: null,
    latestRunStatus: null,
    readiness: null,
    recentRuns: [],
    totalRunCount: 0,
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

function makeSnapshot(overrides: Partial<RoadmapGraphSnapshot> = {}): RoadmapGraphSnapshot {
  return {
    epic: makeEpic(),
    stories: [makeStory()],
    tasks: [makeTask()],
    dependencies: [],
    externalBlockers: [],
    ...overrides,
  };
}

describe("RoadmapGraphPage", () => {
  it("shows a loading skeleton while the graph is loading", () => {
    mockUseRoadmapGraph.mockReturnValue({ data: undefined, isLoading: true });
    renderWithProviders(<RoadmapGraphPage />);
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
  });

  it("shows 'Epic not found' when there is no snapshot", () => {
    mockUseRoadmapGraph.mockReturnValue({ data: undefined, isLoading: false });
    renderWithProviders(<RoadmapGraphPage />);
    expect(screen.getByTestId("roadmap-graph-not-found")).toBeInTheDocument();
  });

  it("opens the detail panel with the clicked node's data and closes it", async () => {
    const user = userEvent.setup();
    mockUseRoadmapGraph.mockReturnValue({ data: makeSnapshot(), isLoading: false });
    renderWithProviders(<RoadmapGraphPage />);

    expect(screen.queryByTestId("roadmap-detail-panel")).not.toBeInTheDocument();

    await user.click(screen.getByTestId("mock-select-task-1"));
    expect(screen.getByTestId("roadmap-detail-title")).toHaveTextContent("Wire up the toggle");
    expect(screen.getByTestId("roadmap-detail-status")).toHaveTextContent("backlog");

    await user.click(screen.getByTestId("roadmap-graph-detail-close"));
    expect(screen.queryByTestId("roadmap-detail-panel")).not.toBeInTheDocument();
  });

  it("keeps the open detail panel in sync with a refreshed snapshot instead of showing stale data", async () => {
    // Regression test: `selected` used to be the RoadmapDetailItem object snapshotted at click
    // time, so it never picked up a fresher `snapshot` returned by a refetch (e.g. the one
    // useRoadmapSubscription triggers on every roadmap-items STOMP event). The graph's nodes
    // re-rendered from the fresh data; the open sidebar didn't. This drives that exact scenario
    // via a rerender with an updated snapshot for the same task id, and asserts the sidebar
    // reflects the new status rather than the one captured when the node was first clicked.
    const user = userEvent.setup();
    const initialSnapshot = makeSnapshot();
    mockUseRoadmapGraph.mockReturnValue({ data: initialSnapshot, isLoading: false });
    const { rerender } = renderWithProviders(<RoadmapGraphPage />);

    await user.click(screen.getByTestId("mock-select-task-1"));
    expect(screen.getByTestId("roadmap-detail-status")).toHaveTextContent("backlog");

    const refreshedSnapshot = makeSnapshot({
      tasks: [makeTask({ status: "done", title: "Wire up the toggle (renamed)" })],
    });
    mockUseRoadmapGraph.mockReturnValue({ data: refreshedSnapshot, isLoading: false });
    rerender(<RoadmapGraphPage />);

    expect(screen.getByTestId("roadmap-detail-status")).toHaveTextContent("done");
    expect(screen.getByTestId("roadmap-detail-title")).toHaveTextContent("Wire up the toggle (renamed)");
  });

  it("closes the detail panel if the selected item disappears from a refreshed snapshot", async () => {
    const user = userEvent.setup();
    const initialSnapshot = makeSnapshot();
    mockUseRoadmapGraph.mockReturnValue({ data: initialSnapshot, isLoading: false });
    const { rerender } = renderWithProviders(<RoadmapGraphPage />);

    await user.click(screen.getByTestId("mock-select-task-1"));
    expect(screen.getByTestId("roadmap-detail-panel")).toBeInTheDocument();

    const refreshedSnapshot = makeSnapshot({ tasks: [] });
    mockUseRoadmapGraph.mockReturnValue({ data: refreshedSnapshot, isLoading: false });
    rerender(<RoadmapGraphPage />);

    expect(screen.queryByTestId("roadmap-detail-panel")).not.toBeInTheDocument();
  });
});
