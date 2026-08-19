import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import StoryListPage from "@/pages/StoryListPage";
import type { PageResponse, StoryResponse } from "@/lib/types";

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

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "desc",
    stage: "in_progress",
    priority: "high",
    targetDate: null,
    readiness: null,
    readyTaskCount: null,
    progress: { totalTasks: 4, doneTasks: 1, startedTasks: 1 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

function makePage(content: StoryResponse[]): PageResponse<StoryResponse> {
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

describe("StoryListPage", () => {
  it("lists every Story in the org from the org-wide listing endpoint", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeStory(), makeStory({ id: "story-2", title: "Second" })]));

    renderWithProviders(<StoryListPage />);

    await waitFor(() => expect(screen.getAllByTestId("story-item")).toHaveLength(2));
    expect(mockApi.getPage).toHaveBeenCalledWith("/stories", { page: 0, size: 20 });
  });

  it("links each row's title to that Story's detail page under its Epic", async () => {
    mockApi.getPage.mockResolvedValue(
      makePage([makeStory({ id: "story-42", epicId: "epic-7", title: "Support OAuth" })]),
    );

    renderWithProviders(<StoryListPage />);

    await waitFor(() => expect(screen.getByText("Support OAuth")).toBeInTheDocument());
    expect(screen.getByText("Support OAuth").closest("a")).toHaveAttribute(
      "href",
      "/roadmap/epics/epic-7/stories/story-42",
    );
  });

  it("renders the shared stage/priority/progress row chrome", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeStory()]));

    renderWithProviders(<StoryListPage />);

    await waitFor(() => expect(screen.getByTestId("story-item-stage")).toBeInTheDocument());
    expect(screen.getByTestId("story-item-priority-badge")).toBeInTheDocument();
    expect(screen.getByTestId("story-item-progress")).toHaveTextContent("1/4 tasks done");
  });

  it("marks itself as the Stories x List surface in the shared header", async () => {
    mockApi.getPage.mockResolvedValue(makePage([]));

    renderWithProviders(<StoryListPage />);

    await waitFor(() => expect(screen.getByTestId("story-list-empty")).toBeInTheDocument());
    expect(screen.getByTestId("roadmap-level-select")).toHaveTextContent("Stories");
    expect(screen.getByTestId("roadmap-view-list")).toHaveAttribute("aria-current", "page");
    expect(screen.getByTestId("roadmap-view-board")).toHaveAttribute("href", "/roadmap/board/stories");
    expect(screen.queryByTestId("roadmap-view-timeline")).not.toBeInTheDocument();
  });
});
