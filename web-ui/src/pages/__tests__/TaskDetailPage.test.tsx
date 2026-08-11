import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import TaskDetailPage from "@/pages/TaskDetailPage";
import type { TaskResponse } from "@/lib/types";

const mockUseTask = vi.fn();
const mockUseStory = vi.fn();
const mockUseTaskRuns = vi.fn();
const mockDeleteMutate = vi.fn();
const mockStartMutate = vi.fn();
const mockCompleteMutate = vi.fn();

vi.mock("@/hooks/useTasks", () => ({
  useTask: (id: string) => mockUseTask(id),
  useDeleteTask: () => ({
    mutate: mockDeleteMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useStartTask: () => ({
    mutate: mockStartMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useCompleteTask: () => ({
    mutate: mockCompleteMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useStories", () => ({
  useStory: (id: string | undefined) => mockUseStory(id),
}));

vi.mock("@/hooks/useTaskRuns", () => ({
  useTaskRuns: (id: string) => mockUseTaskRuns(id),
}));

vi.mock("@/hooks/useRoadmapSubscription", () => ({
  useRoadmapSubscription: vi.fn(),
}));

vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useParams: () => ({ id: "task-1" }),
    useNavigate: () => vi.fn(),
  };
});

beforeEach(() => {
  mockUseTask.mockReset();
  mockUseStory.mockReset();
  mockUseTaskRuns.mockReset();
  mockDeleteMutate.mockReset();
  mockStartMutate.mockReset();
  mockCompleteMutate.mockReset();
  mockUseTaskRuns.mockReturnValue({ data: undefined, isLoading: false });
  mockUseStory.mockReturnValue({ data: undefined });
});

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

describe("TaskDetailPage", () => {
  it("renders task title and status", () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-detail-title")).toHaveTextContent("Implement toggle switch");
    expect(screen.getByTestId("task-detail-status")).toHaveTextContent("backlog");
  });

  it("renders the Task LevelBadge", () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("level-badge-task")).toHaveTextContent("Task");
  });

  it('renders a "Back to Story" link to the parent Story once it resolves', () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    mockUseStory.mockReturnValue({
      data: { id: "story-1", epicId: "epic-1", title: "Parent story" },
    });
    renderWithProviders(<TaskDetailPage />);
    const link = screen.getByRole("link", { name: /Back to Story/ });
    expect(link).toHaveAttribute("href", "/roadmap/epics/epic-1/stories/story-1");
  });

  it('falls back the "Back to Story" link to /roadmap while the parent Story is pending', () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    mockUseStory.mockReturnValue({ data: undefined });
    renderWithProviders(<TaskDetailPage />);
    const link = screen.getByRole("link", { name: /Back to Story/ });
    expect(link).toHaveAttribute("href", "/roadmap");
  });

  it("shows Start and Delete buttons for a backlog task", () => {
    mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-start-button")).toBeInTheDocument();
    expect(screen.getByTestId("task-delete-button")).toBeInTheDocument();
  });

  it("shows a disabled Complete button while the run is not yet terminal-completed", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "in_progress", latestRunStatus: "running" }),
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-complete-button")).toBeDisabled();
    expect(screen.queryByTestId("task-restart-button")).not.toBeInTheDocument();
  });

  it("enables Complete once the latest run has completed", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "in_progress", latestRunStatus: "completed" }),
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-complete-button")).toBeEnabled();
  });

  it("shows Restart when the latest run failed", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "in_progress", latestRunStatus: "failed" }),
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-restart-button")).toBeInTheDocument();
  });

  it("renders run history from useTaskRuns", () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    mockUseTaskRuns.mockReturnValue({
      data: {
        content: [
          {
            id: "run-1",
            graphTemplateId: "tpl-1",
            templateName: "Feature Dev",
            name: null,
            status: "completed",
            startedAt: null,
            completedAt: null,
            createdAt: "2026-04-01T00:00:00Z",
            softwareProject: null,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
        first: true,
        last: true,
        empty: false,
      },
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-run-history-list")).toBeInTheDocument();
    expect(screen.getAllByTestId("task-run-history-item")).toHaveLength(1);
  });
});
