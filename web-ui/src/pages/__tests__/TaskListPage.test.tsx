import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import TaskListPage from "@/pages/TaskListPage";
import type { PageResponse, TaskResponse } from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));
import { api } from "@/lib/api";

vi.mock("@/hooks/useRoadmapSubscription", () => ({
  useRoadmapSubscription: vi.fn(),
}));

const mockApi = api as unknown as { getPage: ReturnType<typeof vi.fn> };

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Wire up the toggle",
    description: "desc",
    status: "in_progress",
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

function makePage(content: TaskResponse[]): PageResponse<TaskResponse> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 20,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("TaskListPage", () => {
  it("lists every Task in the org from the org-wide listing endpoint", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeTask(), makeTask({ id: "task-2", title: "Second" })]));

    renderWithProviders(<TaskListPage />);

    await waitFor(() => expect(screen.getAllByTestId("task-item")).toHaveLength(2));
    expect(mockApi.getPage).toHaveBeenCalledWith("/tasks", { page: 0, size: 20 });
  });

  it("links each row's title to that Task's own detail route", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeTask({ id: "task-42", title: "Support OAuth" })]));

    renderWithProviders(<TaskListPage />);

    await waitFor(() => expect(screen.getByText("Support OAuth")).toBeInTheDocument());
    expect(screen.getByText("Support OAuth").closest("a")).toHaveAttribute("href", "/tasks/task-42");
  });

  it("renders a Task's status, not an Epic-style stage — `done` is a status a stage never is", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeTask({ status: "done" })]));

    renderWithProviders(<TaskListPage />);

    await waitFor(() => expect(screen.getByText("done")).toBeInTheDocument());
  });

  it("shows the latest run's status when the Task has run", async () => {
    mockApi.getPage.mockResolvedValue(
      makePage([makeTask({ latestRunId: "run-1", latestRunStatus: "running" })]),
    );

    renderWithProviders(<TaskListPage />);

    await waitFor(() => expect(screen.getByText("running")).toBeInTheDocument());
  });

  it("marks itself as the Tasks x List surface in the shared header", async () => {
    mockApi.getPage.mockResolvedValue(makePage([]));

    renderWithProviders(<TaskListPage />);

    await waitFor(() => expect(screen.getByTestId("task-list-empty")).toBeInTheDocument());
    expect(screen.getByTestId("roadmap-level-select")).toHaveTextContent("Tasks");
    expect(screen.getByTestId("roadmap-view-list")).toHaveAttribute("aria-current", "page");
    expect(screen.getByTestId("roadmap-view-board")).toHaveAttribute("href", "/roadmap/board/tasks");
    expect(screen.queryByTestId("roadmap-view-timeline")).not.toBeInTheDocument();
  });
});
