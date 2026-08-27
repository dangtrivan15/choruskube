import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import StoryDetailPage from "@/pages/StoryDetailPage";
import type { StoryResponse, TaskResponse } from "@/lib/types";

const mockUseStory = vi.fn();
const mockUseTasks = vi.fn();
const mockDeleteMutate = vi.fn();
const mockStageMutate = vi.fn();

vi.mock("@/hooks/useStories", () => ({
  useStory: (id: string) => mockUseStory(id),
  useUpdateStoryStage: () => ({
    mutate: mockStageMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useDeleteStory: () => ({
    mutate: mockDeleteMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateStoryPriority: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateStoryTargetDate: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useTasks", () => ({
  useTasks: (storyId: string) => mockUseTasks(storyId),
  useCreateTask: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useRoadmapSubscription", () => ({
  useRoadmapSubscription: vi.fn(),
}));

vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useParams: () => ({ epicId: "epic-1", storyId: "story-1" }),
    useNavigate: () => vi.fn(),
  };
});

beforeEach(() => {
  mockUseStory.mockReset();
  mockUseTasks.mockReset();
  mockDeleteMutate.mockReset();
});

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "desc",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    readyTaskCount: null,
    progress: { totalTasks: 2, doneTasks: 1, startedTasks: 1 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Implement toggle switch",
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
    priority: "medium",
    ...overrides,
  };
}

describe("StoryDetailPage", () => {
  it("renders story title, stage, and progress", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("story-detail-title")).toHaveTextContent("Dark theme toggle");
    expect(screen.getByTestId("story-detail-stage")).toHaveTextContent("backlog");
    expect(screen.getByTestId("story-detail-progress")).toHaveTextContent("1/2 tasks done");
  });

  it("renders the Story LevelBadge", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("level-badge-story")).toHaveTextContent("Story");
  });

  it("renders the formatted target date when set", () => {
    mockUseStory.mockReturnValue({
      data: makeStory({ targetDate: "2026-08-13" }),
      isLoading: false,
    });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("story-detail-target-date")).toHaveTextContent("Aug 13, 2026");
  });

  it("renders the empty state when no target date is set", () => {
    mockUseStory.mockReturnValue({ data: makeStory({ targetDate: null }), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("story-detail-target-date")).toHaveTextContent("No target date");
  });

  it('keeps the "Back to Epic" link unchanged', () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    const link = screen.getByRole("link", { name: /Back to Epic/ });
    expect(link).toHaveAttribute("href", "/roadmap/epics/epic-1");
  });

  it("offers the roll-out move once every Task is done but the board still says otherwise", async () => {
    mockUseStory.mockReturnValue({
      data: makeStory({ stage: "in_progress", progress: { totalTasks: 2, doneTasks: 2, startedTasks: 2 } }),
      isLoading: false,
    });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);

    expect(screen.getByTestId("story-detail-stage")).toHaveTextContent("in progress");
    await userEvent.click(screen.getByTestId("story-detail-roll-out-button"));
    expect(mockStageMutate).toHaveBeenCalledWith({ id: "story-1", stage: "rolled_out" });
  });

  it("does not offer the roll-out move once the story is already rolled out", () => {
    mockUseStory.mockReturnValue({
      data: makeStory({ stage: "rolled_out", progress: { totalTasks: 2, doneTasks: 2, startedTasks: 2 } }),
      isLoading: false,
    });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.queryByTestId("story-detail-roll-out")).not.toBeInTheDocument();
  });

  it("shows Delete button while no Task has started", () => {
    mockUseStory.mockReturnValue({
      data: makeStory({ progress: { totalTasks: 2, doneTasks: 0, startedTasks: 0 } }),
      isLoading: false,
    });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("story-delete-button")).toBeInTheDocument();
  });

  it("hides Delete button once a Task has started", () => {
    mockUseStory.mockReturnValue({
      data: makeStory({ progress: { totalTasks: 2, doneTasks: 0, startedTasks: 1 } }),
      isLoading: false,
    });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.queryByTestId("story-delete-button")).not.toBeInTheDocument();
  });

  it("renders the task list with links to each task's detail route", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [makeTask()], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    const item = screen.getByTestId("task-item");
    expect(item).toHaveTextContent("Implement toggle switch");
    expect(item).toHaveAttribute("href", "/tasks/task-1");
  });

  it("shows the Task's priority badge in the task list", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [makeTask({ priority: "high" })], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("task-item-priority-badge")).toHaveTextContent("High");
  });

  it("shows a Blocked badge on a Task row whose readiness is BLOCKED", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [makeTask({ readiness: "BLOCKED" })], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("task-item-readiness-badge")).toHaveTextContent("Blocked");
  });

  it("shows no readiness badge on a Task row whose readiness is READY", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [makeTask({ readiness: "READY" })], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.queryByTestId("task-item-readiness-badge")).not.toBeInTheDocument();
  });

  it("shows no readiness badge on a Task row whose readiness is null", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [makeTask({ readiness: null })], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.queryByTestId("task-item-readiness-badge")).not.toBeInTheDocument();
  });

  it("shows empty state when there are no tasks", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByText(/No tasks yet/)).toBeInTheDocument();
  });

  // --- "Ready to start" filter (client-side, over already-fetched data) ---

  it("toggling the filter hides BLOCKED task rows without re-parameterizing useTasks", async () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({
      data: [
        makeTask({ id: "task-ready", title: "Ready Task", readiness: "READY" }),
        makeTask({ id: "task-blocked", title: "Blocked Task", readiness: "BLOCKED" }),
      ],
      isLoading: false,
    });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByText("Blocked Task")).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.queryByText("Blocked Task")).not.toBeInTheDocument();
    expect(screen.getByText("Ready Task")).toBeInTheDocument();
    // The filter is purely local — useTasks must never be called with anything but the
    // storyId, i.e. toggling never fires a new network request.
    mockUseTasks.mock.calls.forEach((call) => expect(call).toEqual(["story-1"]));
  });

  it("toggling the filter hides Tasks that have already left backlog even when they are READY", async () => {
    // Readiness only says "nothing upstream is still open", which a finished Task satisfies
    // trivially — so the status guard is what keeps this list matching the server's own
    // "ready to start" definition (backlog AND READY).
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({
      data: [
        makeTask({ id: "task-backlog", title: "Backlog Task", status: "backlog", readiness: "READY" }),
        makeTask({ id: "task-running", title: "Running Task", status: "in_progress", readiness: "READY" }),
        makeTask({ id: "task-done", title: "Done Task", status: "done", readiness: "READY" }),
      ],
      isLoading: false,
    });
    renderWithProviders(<StoryDetailPage />);

    const user = userEvent.setup();
    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.getByText("Backlog Task")).toBeInTheDocument();
    expect(screen.queryByText("Running Task")).not.toBeInTheDocument();
    expect(screen.queryByText("Done Task")).not.toBeInTheDocument();
  });

  it("shows filter-specific empty-state copy when the filter yields zero results despite non-empty task data", async () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [makeTask({ readiness: "BLOCKED" })], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    const user = userEvent.setup();

    expect(screen.queryByText(/No tasks are ready to start/)).not.toBeInTheDocument();

    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.getByText(/No tasks are ready to start/)).toBeInTheDocument();
  });
});
