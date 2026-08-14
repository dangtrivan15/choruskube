import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import StoryBoardCard from "@/components/roadmap/StoryBoardCard";
import type { StoryResponse, TaskResponse } from "@/lib/types";

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

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
};

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "Add a dark theme toggle",
    status: "backlog",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    progress: { totalTasks: 3, doneTasks: 1 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Implement toggle component",
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

beforeEach(() => {
  vi.clearAllMocks();
});

describe("StoryBoardCard", () => {
  it("renders the title and 'X of Y tasks complete' progress", () => {
    renderWithProviders(<StoryBoardCard story={makeStory()} />);
    expect(screen.getByTestId("story-board-card-title")).toHaveTextContent("Dark theme toggle");
    expect(screen.getByTestId("story-board-card-progress")).toHaveTextContent(
      "1 of 3 tasks complete"
    );
  });

  it("renders the story-board-card testid, draggable via dnd-kit's listeners/attributes", () => {
    renderWithProviders(<StoryBoardCard story={makeStory({ id: "story-42" })} />);
    const card = screen.getByTestId("story-board-card");
    expect(card).toBeInTheDocument();
    expect(card).toHaveAttribute("data-story-id", "story-42");
    // dnd-kit's useDraggable spreads a `tabIndex` from its `attributes` object onto whatever
    // element they're applied to — a lightweight signal (without over-asserting on dnd-kit's
    // internal attribute set) that the card is wired up as a drag source.
    expect(card).toHaveAttribute("tabIndex");
  });

  it("does not fetch tasks until expanded", () => {
    renderWithProviders(<StoryBoardCard story={makeStory()} />);
    expect(mockApi.get).not.toHaveBeenCalled();
    expect(screen.queryByTestId("story-board-card-tasks")).not.toBeInTheDocument();
  });

  it("expanding shows Task rows with their status", async () => {
    mockApi.get.mockResolvedValue([makeTask()]);
    renderWithProviders(<StoryBoardCard story={makeStory()} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("story-board-card-expand"));

    await waitFor(() => expect(screen.getByTestId("story-board-card-task")).toBeInTheDocument());
    expect(screen.getByTestId("story-board-card-task")).toHaveTextContent(
      "Implement toggle component"
    );
    expect(screen.getByTestId("story-board-card-task-status")).toHaveTextContent("backlog");
    expect(mockApi.get).toHaveBeenCalledWith("/stories/story-1/tasks");
  });

  it("shows a blocked badge on a Task whose readiness is BLOCKED", async () => {
    mockApi.get.mockResolvedValue([makeTask({ readiness: "BLOCKED" })]);
    renderWithProviders(<StoryBoardCard story={makeStory()} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("story-board-card-expand"));

    await waitFor(() =>
      expect(screen.getByTestId("story-board-card-task-blocked")).toBeInTheDocument()
    );
  });

  it("does not show a blocked badge on a Task whose readiness is READY", async () => {
    mockApi.get.mockResolvedValue([makeTask({ readiness: "READY" })]);
    renderWithProviders(<StoryBoardCard story={makeStory()} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("story-board-card-expand"));

    await waitFor(() => expect(screen.getByTestId("story-board-card-task")).toBeInTheDocument());
    expect(screen.queryByTestId("story-board-card-task-blocked")).not.toBeInTheDocument();
  });

  it("collapsing hides Tasks, and re-expanding reuses the cached query (fetched once)", async () => {
    mockApi.get.mockResolvedValue([makeTask()]);
    renderWithProviders(<StoryBoardCard story={makeStory()} />);
    const user = userEvent.setup();

    // Expand
    await user.click(screen.getByTestId("story-board-card-expand"));
    await waitFor(() => expect(screen.getByTestId("story-board-card-task")).toBeInTheDocument());
    expect(mockApi.get).toHaveBeenCalledTimes(1);

    // Collapse
    await user.click(screen.getByTestId("story-board-card-expand"));
    expect(screen.queryByTestId("story-board-card-tasks")).not.toBeInTheDocument();

    // Re-expand — no duplicate fetch, cache is reused.
    await user.click(screen.getByTestId("story-board-card-expand"));
    await waitFor(() => expect(screen.getByTestId("story-board-card-task")).toBeInTheDocument());
    expect(mockApi.get).toHaveBeenCalledTimes(1);
  });
});
