import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import TaskBoardPage from "@/pages/TaskBoardPage";
import type { PageResponse, TaskResponse } from "@/lib/types";

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
        active: { id: string; data: { current?: { status?: string } } };
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

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Implement dark mode toggle",
    description: "Add a toggle control",
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

function makePage(content: TaskResponse[]): PageResponse<TaskResponse> {
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

describe("TaskBoardPage", () => {
  it("renders three columns with Tasks grouped by status", async () => {
    const tasks = [
      makeTask({ id: "task-1", title: "Backlog Task", status: "backlog" }),
      makeTask({ id: "task-2", title: "In Progress Task", status: "in_progress" }),
      makeTask({ id: "task-3", title: "Done Task", status: "done" }),
    ];
    mockApi.getPage.mockResolvedValueOnce(makePage(tasks));

    renderWithProviders(<TaskBoardPage />);

    await waitFor(() => expect(screen.getByTestId("task-board")).toBeInTheDocument());

    expect(
      within(screen.getByTestId("board-column-backlog")).getByText("Backlog Task")
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("board-column-in_progress")).getByText("In Progress Task")
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("board-column-done")).getByText("Done Task")
    ).toBeInTheDocument();
  });

  it("an unrecognized status is skipped from the board instead of crashing the page", async () => {
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    const known = makeTask({ id: "task-1", title: "Known Task", status: "backlog" });
    // Simulate a status value the frontend's inline literal union doesn't model yet —
    // real API responses aren't statically typed at runtime, so an `as` cast stands in.
    const unknown = makeTask({
      id: "task-2",
      title: "Future-Status Task",
      status: "archived" as TaskResponse["status"],
    });
    mockApi.getPage.mockResolvedValueOnce(makePage([known, unknown]));

    renderWithProviders(<TaskBoardPage />);

    await waitFor(() => expect(screen.getByTestId("task-board")).toBeInTheDocument());
    expect(
      within(screen.getByTestId("board-column-backlog")).getByText("Known Task")
    ).toBeInTheDocument();
    expect(screen.queryByText("Future-Status Task")).not.toBeInTheDocument();
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("task-2"));

    warnSpy.mockRestore();
  });

  it("a successful drag calls the status-update mutation and the card ends up in the new column", async () => {
    const task = makeTask({ id: "task-1", title: "Movable Task", status: "backlog" });
    // 1st call: initial load. 2nd call: the refetch `onSettled` triggers after the
    // mutation resolves — reflects the server's new persisted status, like a real backend would.
    mockApi.getPage
      .mockResolvedValueOnce(makePage([task]))
      .mockResolvedValueOnce(makePage([{ ...task, status: "in_progress" }]));
    mockApi.patch.mockResolvedValueOnce({ ...task, status: "in_progress" });

    renderWithProviders(<TaskBoardPage />);

    await waitFor(() => expect(screen.getByText("Movable Task")).toBeInTheDocument());
    expect(dndState.onDragEnd).toBeDefined();

    dndState.onDragEnd!({
      active: { id: "task-1", data: { current: { status: "backlog" } } },
      over: { id: "in_progress" },
    });

    await waitFor(() =>
      expect(mockApi.patch).toHaveBeenCalledWith("/tasks/task-1/status", { status: "in_progress" })
    );

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-in_progress")).getByText("Movable Task")
      ).toBeInTheDocument()
    );
    expect(
      within(screen.getByTestId("board-column-backlog")).queryByText("Movable Task")
    ).not.toBeInTheDocument();
  });

  it("dropping a card back into its own column is a no-op and does not call the mutation", async () => {
    const task = makeTask({ id: "task-1", title: "Stationary Task", status: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([task]));

    renderWithProviders(<TaskBoardPage />);
    await waitFor(() => expect(screen.getByText("Stationary Task")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "task-1", data: { current: { status: "backlog" } } },
      over: { id: "backlog" },
    });

    expect(mockApi.patch).not.toHaveBeenCalled();
  });

  it("a failed mutation rolls the card back to its original column", async () => {
    const task = makeTask({ id: "task-1", title: "Rollback Task", status: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([task]));
    mockApi.patch.mockRejectedValueOnce(new Error("boom"));

    renderWithProviders(<TaskBoardPage />);
    await waitFor(() => expect(screen.getByText("Rollback Task")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "task-1", data: { current: { status: "backlog" } } },
      over: { id: "in_progress" },
    });

    await waitFor(() =>
      expect(mockApi.patch).toHaveBeenCalledWith("/tasks/task-1/status", { status: "in_progress" })
    );

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-backlog")).getByText("Rollback Task")
      ).toBeInTheDocument()
    );
    expect(
      within(screen.getByTestId("board-column-in_progress")).queryByText("Rollback Task")
    ).not.toBeInTheDocument();
  });

  it("a roadmap-items STOMP message triggers a refetch", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeTask()]));

    renderWithProviders(<TaskBoardPage />);
    await waitFor(() => expect(mockApi.getPage).toHaveBeenCalledTimes(1));
    expect(stompState.callback).toBeDefined();

    stompState.callback!({ body: "{}" });

    await waitFor(() => expect(mockApi.getPage).toHaveBeenCalledTimes(2));
  });
});
