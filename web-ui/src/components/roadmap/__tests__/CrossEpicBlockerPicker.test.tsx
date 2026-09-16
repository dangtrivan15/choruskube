import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CrossEpicBlockerPicker from "@/components/roadmap/CrossEpicBlockerPicker";
import type { EpicResponse, RoadmapGraphSnapshot, StoryResponse } from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
    post: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

import { api } from "@/lib/api";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
};

const currentEpic: EpicResponse = {
  id: "epic-1",
  title: "Add dark mode",
  description: "d",
  motivation: null,
  stage: "in_progress",
  priority: "medium",
  targetDate: null,
  progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
  softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
  repos: [],
  createdAt: "2026-04-01T00:00:00Z",
  updatedAt: "2026-04-01T00:00:00Z",
  readyItemCount: 0,
  milestone: null,
};

const otherEpic: EpicResponse = { ...currentEpic, id: "epic-2", title: "Auth Overhaul" };

const otherStory: StoryResponse = {
  id: "story-2",
  epicId: "epic-2",
  title: "Migrate auth service",
  description: "d",
  stage: "backlog",
  priority: "medium",
  targetDate: null,
  readiness: "READY",
  readyTaskCount: 0,
  progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
  createdAt: "2026-04-01T00:00:00Z",
  updatedAt: "2026-04-01T00:00:00Z",
};

const otherEpicSnapshot: RoadmapGraphSnapshot = {
  epic: otherEpic,
  stories: [otherStory],
  tasks: [],
  dependencies: [],
  externalBlockers: [],
};

beforeEach(() => {
  vi.clearAllMocks();
  mockApi.getPage.mockResolvedValue({
    content: [currentEpic, otherEpic],
    totalElements: 2,
    totalPages: 1,
    number: 0,
  });
});

function renderPicker() {
  return renderWithProviders(
    <CrossEpicBlockerPicker blockedItemType="task" blockedItemId="task-1" currentEpicId="epic-1" />,
  );
}

describe("CrossEpicBlockerPicker", () => {
  it("offers other Epics as targets and excludes the current Epic", async () => {
    const user = userEvent.setup();
    renderPicker();

    await user.click(screen.getByTestId("roadmap-cross-epic-target-select"));

    expect(await screen.findByText("Auth Overhaul")).toBeInTheDocument();
    expect(screen.queryByText("Add dark mode")).not.toBeInTheDocument();
  });

  it("creates a cross-Epic dependency for the chosen blocker item in the target Epic", async () => {
    mockApi.get.mockResolvedValue(otherEpicSnapshot);
    mockApi.post.mockResolvedValue({
      id: "dep-x",
      blockingItemType: "story",
      blockingItemId: "story-2",
      blockedItemType: "task",
      blockedItemId: "task-1",
      createdAt: "2026-04-01T00:00:00Z",
    });
    const user = userEvent.setup();
    renderPicker();

    await user.click(screen.getByTestId("roadmap-cross-epic-target-select"));
    await user.click(await screen.findByText("Auth Overhaul"));

    // Selecting the target Epic fetches its graph for the second picker.
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith("/epics/epic-2/graph"));

    await user.click(screen.getByTestId("roadmap-cross-epic-item-select"));
    await user.click(await screen.findByText("Migrate auth service (story)"));
    await user.click(screen.getByTestId("roadmap-cross-epic-submit"));

    await waitFor(() =>
      expect(mockApi.post).toHaveBeenCalledWith("/dependencies", {
        blockingItemType: "story",
        blockingItemId: "story-2",
        blockedItemType: "task",
        blockedItemId: "task-1",
      }),
    );
  });
});
