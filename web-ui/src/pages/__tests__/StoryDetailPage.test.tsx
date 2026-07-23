import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import StoryDetailPage from "@/pages/StoryDetailPage";
import type { StoryResponse, TaskResponse } from "@/lib/types";

const mockUseStory = vi.fn();
const mockUseTasks = vi.fn();
const mockDeleteMutate = vi.fn();

vi.mock("@/hooks/useStories", () => ({
  useStory: (id: string) => mockUseStory(id),
  useDeleteStory: () => ({
    mutate: mockDeleteMutate,
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
    status: "backlog",
    readiness: null,
    progress: { totalTasks: 2, doneTasks: 1 },
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
    ...overrides,
  };
}

describe("StoryDetailPage", () => {
  it("renders story title, status, and progress", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("story-detail-title")).toHaveTextContent("Dark theme toggle");
    expect(screen.getByTestId("story-detail-status")).toHaveTextContent("backlog");
    expect(screen.getByTestId("story-detail-progress")).toHaveTextContent("1/2 tasks done");
  });

  it("shows Delete button when the story is in backlog", () => {
    mockUseStory.mockReturnValue({ data: makeStory({ status: "backlog" }), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByTestId("story-delete-button")).toBeInTheDocument();
  });

  it("hides Delete button once the story has left backlog", () => {
    mockUseStory.mockReturnValue({ data: makeStory({ status: "in_progress" }), isLoading: false });
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

  it("shows empty state when there are no tasks", () => {
    mockUseStory.mockReturnValue({ data: makeStory(), isLoading: false });
    mockUseTasks.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<StoryDetailPage />);
    expect(screen.getByText(/No tasks yet/)).toBeInTheDocument();
  });
});
