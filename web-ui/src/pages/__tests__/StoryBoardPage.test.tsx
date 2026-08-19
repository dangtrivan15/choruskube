import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import StoryBoardPage from "@/pages/StoryBoardPage";
import type { PageResponse, StoryResponse } from "@/lib/types";

// --- @/lib/api ---------------------------------------------------------
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
  getPage: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
};

// --- @/lib/stomp — capture the roadmap-items subscription callback -----
const stompState = vi.hoisted(() => ({
  callback: undefined as ((msg: { body: string }) => void) | undefined,
}));
vi.mock("@/lib/stomp", () => ({
  subscribe: (_topic: string, cb: (msg: { body: string }) => void) => {
    stompState.callback = cb;
    return () => {};
  },
}));

// --- @dnd-kit/core — capture onDragEnd so tests can simulate a drop ----
const dndState = vi.hoisted(() => ({
  onDragEnd: undefined as
    | ((event: {
        active: { id: string; data: { current?: { stage?: string } } };
        over: { id: string } | null;
      }) => void)
    | undefined,
}));
vi.mock("@dnd-kit/core", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@dnd-kit/core")>();
  return {
    ...actual,
    DndContext: ({
      children,
      onDragEnd,
    }: {
      children: React.ReactNode;
      onDragEnd: (event: unknown) => void;
    }) => {
      dndState.onDragEnd = onDragEnd as typeof dndState.onDragEnd;
      return children;
    },
  };
});

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "Add a dark theme toggle",
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

function makePage(content: StoryResponse[]): PageResponse<StoryResponse> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 200,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  dndState.onDragEnd = undefined;
  stompState.callback = undefined;
});

describe("StoryBoardPage", () => {
  it("renders three columns with Stories grouped by stage", async () => {
    const stories = [
      makeStory({ id: "story-1", title: "Backlog Story", stage: "backlog" }),
      makeStory({ id: "story-2", title: "In Progress Story", stage: "in_progress" }),
      makeStory({ id: "story-3", title: "Rolled Out Story", stage: "rolled_out" }),
    ];
    mockApi.getPage.mockResolvedValueOnce(makePage(stories));

    renderWithProviders(<StoryBoardPage />);

    await waitFor(() => expect(screen.getByTestId("story-board")).toBeInTheDocument());

    expect(
      within(screen.getByTestId("board-column-backlog")).getByText("Backlog Story")
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("board-column-in_progress")).getByText("In Progress Story")
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("board-column-rolled_out")).getByText("Rolled Out Story")
    ).toBeInTheDocument();
  });

  it("an unrecognized stage is skipped from the board instead of crashing the page", async () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    const known = makeStory({ id: "story-1", title: "Known Story", stage: "backlog" });
    // Simulate a stage value the frontend's inline literal union doesn't model yet — real API
    // responses aren't statically typed at runtime, so an `as` cast stands in.
    const unknown = makeStory({
      id: "story-2",
      title: "Future-Stage Story",
      stage: "archived" as StoryResponse["stage"],
    });
    mockApi.getPage.mockResolvedValueOnce(makePage([known, unknown]));

    renderWithProviders(<StoryBoardPage />);

    await waitFor(() => expect(screen.getByTestId("story-board")).toBeInTheDocument());
    expect(
      within(screen.getByTestId("board-column-backlog")).getByText("Known Story")
    ).toBeInTheDocument();
    expect(screen.queryByText("Future-Stage Story")).not.toBeInTheDocument();
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("story-2"));

    warnSpy.mockRestore();
  });

  it("a successful drag calls the stage-update mutation and the card ends up in the new column", async () => {
    const story = makeStory({ id: "story-1", title: "Movable Story", stage: "backlog" });
    // 1st call: initial load. 2nd call: the refetch `onSettled` triggers after the
    // mutation resolves — reflects the server's new persisted stage, like a real backend would.
    mockApi.getPage
      .mockResolvedValueOnce(makePage([story]))
      .mockResolvedValueOnce(makePage([{ ...story, stage: "in_progress" }]));
    mockApi.patch.mockResolvedValueOnce({ ...story, stage: "in_progress" });

    renderWithProviders(<StoryBoardPage />);

    await waitFor(() => expect(screen.getByText("Movable Story")).toBeInTheDocument());
    expect(dndState.onDragEnd).toBeDefined();

    dndState.onDragEnd!({
      active: { id: "story-1", data: { current: { stage: "backlog" } } },
      over: { id: "in_progress" },
    });

    await waitFor(() =>
      expect(mockApi.patch).toHaveBeenCalledWith("/stories/story-1/stage", { stage: "in_progress" })
    );

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-in_progress")).getByText("Movable Story")
      ).toBeInTheDocument()
    );
    expect(
      within(screen.getByTestId("board-column-backlog")).queryByText("Movable Story")
    ).not.toBeInTheDocument();
  });

  it("dropping a card back into its own column is a no-op and does not call the mutation", async () => {
    const story = makeStory({ id: "story-1", title: "Stationary Story", stage: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([story]));

    renderWithProviders(<StoryBoardPage />);
    await waitFor(() => expect(screen.getByText("Stationary Story")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "story-1", data: { current: { stage: "backlog" } } },
      over: { id: "backlog" },
    });

    expect(mockApi.patch).not.toHaveBeenCalled();
  });

  it("a failed mutation rolls the card back to its original column", async () => {
    const story = makeStory({ id: "story-1", title: "Rollback Story", stage: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([story]));
    mockApi.patch.mockRejectedValueOnce(new Error("boom"));

    renderWithProviders(<StoryBoardPage />);
    await waitFor(() => expect(screen.getByText("Rollback Story")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "story-1", data: { current: { stage: "backlog" } } },
      over: { id: "in_progress" },
    });

    await waitFor(() =>
      expect(mockApi.patch).toHaveBeenCalledWith("/stories/story-1/stage", { stage: "in_progress" })
    );

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-backlog")).getByText("Rollback Story")
      ).toBeInTheDocument()
    );
    expect(
      within(screen.getByTestId("board-column-in_progress")).queryByText("Rollback Story")
    ).not.toBeInTheDocument();
  });

  it("a roadmap-items STOMP message triggers a refetch", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeStory()]));

    renderWithProviders(<StoryBoardPage />);
    await waitFor(() => expect(mockApi.getPage).toHaveBeenCalledTimes(1));
    expect(stompState.callback).toBeDefined();

    stompState.callback!({ body: "{}" });

    await waitFor(() => expect(mockApi.getPage).toHaveBeenCalledTimes(2));
  });
});
