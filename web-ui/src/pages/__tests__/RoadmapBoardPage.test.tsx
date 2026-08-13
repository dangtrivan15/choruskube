import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapBoardPage from "@/pages/RoadmapBoardPage";
import type { EpicResponse, EpicStage, PageResponse, StoryResponse } from "@/lib/types";

// Spies on every `useSearchParams()` call in the rendered tree — mirrors
// RoadmapTimelinePage.test.tsx's identical spy; see that file's comment for why this captures the
// raw `URLSearchParams#toString()` output rather than just the parsed focus.
const searchParamsSpy = vi.hoisted(() => ({
  calls: [] as Array<{ init: unknown; options: unknown }>,
  latestSearch: "",
}));
vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useSearchParams: (...args: Parameters<typeof actual.useSearchParams>) => {
      const [params, setParams] = actual.useSearchParams(...args);
      searchParamsSpy.latestSearch = params.toString();
      const spiedSetParams: typeof setParams = (init, options) => {
        searchParamsSpy.calls.push({ init, options });
        setParams(init, options);
      };
      return [params, spiedSetParams];
    },
  };
});

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
    | ((event: { active: { id: string; data: { current?: { stage?: string } } }; over: { id: string } | null }) => void)
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

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Add a dark theme",
    motivation: null,
    status: "backlog",
    stage: "backlog",
    priority: "medium",
    progress: { totalTasks: 3, doneTasks: 1 },
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
    status: "backlog",
    stage: "backlog",
    priority: "medium",
    readiness: null,
    progress: { totalTasks: 2, doneTasks: 1 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

function makePage(content: EpicResponse[]): PageResponse<EpicResponse> {
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
  searchParamsSpy.calls = [];
  searchParamsSpy.latestSearch = "";
});

describe("RoadmapBoardPage", () => {
  it("renders three columns with Epics placed by stage", async () => {
    const epics = [
      makeEpic({ id: "epic-1", title: "Backlog Epic", stage: "backlog" }),
      makeEpic({ id: "epic-2", title: "In Progress Epic", stage: "in_progress" }),
      makeEpic({ id: "epic-3", title: "Rolled Out Epic", stage: "rolled_out" }),
    ];
    mockApi.getPage.mockResolvedValueOnce(makePage(epics));

    renderWithProviders(<RoadmapBoardPage />);

    await waitFor(() => expect(screen.getByTestId("roadmap-board")).toBeInTheDocument());

    expect(
      within(screen.getByTestId("board-column-backlog")).getByText("Backlog Epic")
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("board-column-in_progress")).getByText("In Progress Epic")
    ).toBeInTheDocument();
    expect(
      within(screen.getByTestId("board-column-rolled_out")).getByText("Rolled Out Epic")
    ).toBeInTheDocument();
  });

  it("expanding a card fetches and shows its Stories' mini progress", async () => {
    mockApi.getPage.mockResolvedValueOnce(makePage([makeEpic()]));
    mockApi.get.mockResolvedValueOnce([makeStory()]);

    renderWithProviders(<RoadmapBoardPage />);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("epic-board-card-expand")).toBeInTheDocument());
    await user.click(screen.getByTestId("epic-board-card-expand"));

    await waitFor(() => expect(screen.getByTestId("epic-board-card-story")).toBeInTheDocument());
    expect(screen.getByTestId("epic-board-card-story-progress")).toHaveTextContent("1/2");
    expect(mockApi.get).toHaveBeenCalledWith("/epics/epic-1/stories");
  });

  it("a successful drag calls the stage-update mutation and the card ends up in the new column", async () => {
    const epic = makeEpic({ id: "epic-1", title: "Movable Epic", stage: "backlog" });
    // 1st call: initial load. 2nd call: the refetch `onSettled` triggers after the
    // mutation resolves — reflects the server's new persisted stage, like a real backend would.
    mockApi.getPage
      .mockResolvedValueOnce(makePage([epic]))
      .mockResolvedValueOnce(makePage([{ ...epic, stage: "in_progress" }]));
    mockApi.patch.mockResolvedValueOnce({ ...epic, stage: "in_progress" });

    renderWithProviders(<RoadmapBoardPage />);

    await waitFor(() => expect(screen.getByText("Movable Epic")).toBeInTheDocument());
    expect(dndState.onDragEnd).toBeDefined();

    dndState.onDragEnd!({
      active: { id: "epic-1", data: { current: { stage: "backlog" } } },
      over: { id: "in_progress" },
    });

    // `mutate()` kicks off `onMutate` asynchronously — the underlying api.patch
    // call is not necessarily made in the same tick as the onDragEnd call above.
    await waitFor(() =>
      expect(mockApi.patch).toHaveBeenCalledWith("/epics/epic-1/stage", { stage: "in_progress" })
    );

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-in_progress")).getByText("Movable Epic")
      ).toBeInTheDocument()
    );
    expect(
      within(screen.getByTestId("board-column-backlog")).queryByText("Movable Epic")
    ).not.toBeInTheDocument();
  });

  it("dropping a card back into its own column is a no-op and does not call the mutation", async () => {
    const epic = makeEpic({ id: "epic-1", title: "Stationary Epic", stage: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([epic]));

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(screen.getByText("Stationary Epic")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "epic-1", data: { current: { stage: "backlog" } } },
      over: { id: "backlog" },
    });

    expect(mockApi.patch).not.toHaveBeenCalled();
  });

  it("a failed mutation rolls the card back to its original column", async () => {
    const epic = makeEpic({ id: "epic-1", title: "Rollback Epic", stage: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([epic]));
    mockApi.patch.mockRejectedValueOnce(new Error("boom"));

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(screen.getByText("Rollback Epic")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "epic-1", data: { current: { stage: "backlog" } } },
      over: { id: "in_progress" },
    });

    // The mutation must actually run (and fail) — otherwise this assertion would
    // pass trivially, since the card started in "backlog" and never left.
    await waitFor(() =>
      expect(mockApi.patch).toHaveBeenCalledWith("/epics/epic-1/stage", { stage: "in_progress" })
    );

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-backlog")).getByText("Rollback Epic")
      ).toBeInTheDocument()
    );
    expect(
      within(screen.getByTestId("board-column-in_progress")).queryByText("Rollback Epic")
    ).not.toBeInTheDocument();
  });

  it("an epic with an unrecognized stage is skipped from the board instead of crashing the page", async () => {
    // `stage` is a Postgres enum extended via ALTER TYPE ... ADD VALUE (see backend CLAUDE.md);
    // during a rolling deploy an older frontend build can receive a value it doesn't know about
    // yet. The board must degrade gracefully (drop the card, keep rendering) rather than throw
    // when indexing its per-column groups with an unrecognized key.
    const warnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    const known = makeEpic({ id: "epic-1", title: "Known Epic", stage: "backlog" });
    // Simulate a stage value the frontend's EpicStage union doesn't model yet — real API
    // responses aren't statically typed at runtime, so an `as EpicStage` cast stands in for that.
    const unknown = makeEpic({
      id: "epic-2",
      title: "Future-Stage Epic",
      stage: "archived" as EpicStage,
    });
    mockApi.getPage.mockResolvedValueOnce(makePage([known, unknown]));

    renderWithProviders(<RoadmapBoardPage />);

    await waitFor(() => expect(screen.getByTestId("roadmap-board")).toBeInTheDocument());
    expect(
      within(screen.getByTestId("board-column-backlog")).getByText("Known Epic")
    ).toBeInTheDocument();
    expect(screen.queryByText("Future-Stage Epic")).not.toBeInTheDocument();
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining("epic-2"));

    warnSpy.mockRestore();
  });

  it("a roadmap-items STOMP message triggers a refetch", async () => {
    mockApi.getPage.mockResolvedValue(makePage([makeEpic()]));

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(mockApi.getPage).toHaveBeenCalledTimes(1));
    expect(stompState.callback).toBeDefined();

    stompState.callback!({ body: "{}" });

    await waitFor(() => expect(mockApi.getPage).toHaveBeenCalledTimes(2));
  });

  // --- "Ready to start" filter ---

  it("toggling the filter reduces visible cards per column", async () => {
    const readyEpic = makeEpic({ id: "epic-1", title: "Ready Epic", stage: "backlog" });
    const blockedEpic = makeEpic({ id: "epic-2", title: "Blocked Epic", stage: "backlog" });
    mockApi.getPage.mockImplementation((path: string) =>
      Promise.resolve(
        path.includes("readiness=READY") ? makePage([readyEpic]) : makePage([readyEpic, blockedEpic])
      )
    );

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(screen.getByText("Blocked Epic")).toBeInTheDocument());

    const user = userEvent.setup();
    await user.click(screen.getByTestId("ready-to-start-toggle"));

    await waitFor(() => expect(screen.queryByText("Blocked Epic")).not.toBeInTheDocument());
    expect(screen.getByText("Ready Epic")).toBeInTheDocument();
  });

  it("existing drag-and-drop still works with the toggle off (regression guard)", async () => {
    const epic = makeEpic({ id: "epic-1", title: "Off-State Movable Epic", stage: "backlog" });
    mockApi.getPage
      .mockResolvedValueOnce(makePage([epic]))
      .mockResolvedValueOnce(makePage([{ ...epic, stage: "in_progress" }]));
    mockApi.patch.mockResolvedValueOnce({ ...epic, stage: "in_progress" });

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(screen.getByText("Off-State Movable Epic")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "epic-1", data: { current: { stage: "backlog" } } },
      over: { id: "in_progress" },
    });

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-in_progress")).getByText("Off-State Movable Epic")
      ).toBeInTheDocument()
    );
  });

  it("dragging a card with the 'Ready to start' toggle ON optimistically updates the filtered (readyOnly: true) board before the mutation resolves", async () => {
    // Regression guard for boardEpicsQueryKey's readyOnly parameterization (Task 9): if the
    // optimistic update wrote to the unfiltered cache entry while the board is actively
    // rendering the readyOnly:true one, this move would not be visible until the mutation
    // settles and a refetch reconciles it — so asserting it *before* the patch resolves
    // proves the optimistic write targeted the right entry.
    const epic = makeEpic({ id: "epic-1", title: "Ready Movable Epic", stage: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([epic]));
    let resolvePatch: (value: EpicResponse) => void;
    mockApi.patch.mockReturnValueOnce(
      new Promise<EpicResponse>((resolve) => {
        resolvePatch = resolve;
      })
    );

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(screen.getByText("Ready Movable Epic")).toBeInTheDocument());

    const user = userEvent.setup();
    await user.click(screen.getByTestId("ready-to-start-toggle"));
    await waitFor(() =>
      expect(mockApi.getPage).toHaveBeenLastCalledWith(
        expect.stringContaining("readiness=READY"),
        expect.anything()
      )
    );

    dndState.onDragEnd!({
      active: { id: "epic-1", data: { current: { stage: "backlog" } } },
      over: { id: "in_progress" },
    });

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-in_progress")).getByText("Ready Movable Epic")
      ).toBeInTheDocument()
    );

    resolvePatch!({ ...epic, stage: "in_progress" });
  });

  it("dragging a card with the toggle OFF optimistically updates the unfiltered (readyOnly: false) board before the mutation resolves", async () => {
    // Companion to the ON-state case above — a regression guard specifically for making
    // `readyOnly` a required (not optional) parameter in Task 9: an implicit-`undefined`
    // call would only surface as a cache-key mismatch in this off-state case, not the
    // on-state one, since the off state is TanStack Query's default query key already.
    const epic = makeEpic({ id: "epic-1", title: "Off Movable Epic", stage: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([epic]));
    let resolvePatch: (value: EpicResponse) => void;
    mockApi.patch.mockReturnValueOnce(
      new Promise<EpicResponse>((resolve) => {
        resolvePatch = resolve;
      })
    );

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(screen.getByText("Off Movable Epic")).toBeInTheDocument());

    dndState.onDragEnd!({
      active: { id: "epic-1", data: { current: { stage: "backlog" } } },
      over: { id: "in_progress" },
    });

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-in_progress")).getByText("Off Movable Epic")
      ).toBeInTheDocument()
    );

    resolvePatch!({ ...epic, stage: "in_progress" });
  });

  it("shows filter-aware empty-column copy for empty stages when the 'Ready to start' filter is active", async () => {
    const epic = makeEpic({ id: "epic-1", title: "Ready Epic", stage: "backlog" });
    mockApi.getPage.mockResolvedValue(makePage([epic]));

    renderWithProviders(<RoadmapBoardPage />);
    await waitFor(() => expect(screen.getByText("Ready Epic")).toBeInTheDocument());
    expect(
      within(screen.getByTestId("board-column-in_progress")).getByText("No epics")
    ).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByTestId("ready-to-start-toggle"));

    await waitFor(() =>
      expect(
        within(screen.getByTestId("board-column-in_progress")).getByText("Nothing ready in this stage")
      ).toBeInTheDocument()
    );
  });

  // --- Focus / RoadmapViewSwitcher wiring ---

  describe("focus", () => {
    it("with ?epic=epic-1 in the URL, that Epic's card receives the highlight/isFocused treatment", async () => {
      const epics = [
        makeEpic({ id: "epic-1", title: "Focused Epic" }),
        makeEpic({ id: "epic-2", title: "Other Epic" }),
      ];
      mockApi.getPage.mockResolvedValue(makePage(epics));

      renderWithProviders(<RoadmapBoardPage />, { initialEntries: ["/roadmap/board?epic=epic-1"] });

      await waitFor(() =>
        expect(screen.getByText("Focused Epic").closest('[data-testid="epic-board-card"]')).toHaveAttribute(
          "data-focused",
          "true",
        )
      );
      expect(screen.getByText("Other Epic").closest('[data-testid="epic-board-card"]')).toHaveAttribute(
        "data-focused",
        "false",
      );
    });

    it("clicking a card updates the epic query param via history replace, not push", async () => {
      mockApi.getPage.mockResolvedValue(makePage([makeEpic({ id: "epic-1", title: "Clickable Epic" })]));
      const user = userEvent.setup();

      renderWithProviders(<RoadmapBoardPage />);
      await waitFor(() => expect(screen.getByText("Clickable Epic")).toBeInTheDocument());

      await user.click(screen.getByTestId("epic-board-card-title"));

      await waitFor(() => expect(searchParamsSpy.latestSearch).toBe("epic=epic-1"));
      expect(searchParamsSpy.calls[searchParamsSpy.calls.length - 1]?.options).toEqual({ replace: true });
    });

    it("with ?epic=epic-1&story=story-1, that Epic's card renders expanded with story-1 highlighted", async () => {
      mockApi.getPage.mockResolvedValue(makePage([makeEpic({ id: "epic-1", title: "Story-Focused Epic" })]));
      mockApi.get.mockResolvedValue([
        makeStory({ id: "story-1", title: "The Focused Story" }),
        makeStory({ id: "story-2", title: "Another Story" }),
      ]);

      renderWithProviders(<RoadmapBoardPage />, {
        initialEntries: ["/roadmap/board?epic=epic-1&story=story-1"],
      });

      await waitFor(() => expect(screen.getByTestId("epic-board-card-stories")).toBeInTheDocument());
      const rows = screen.getAllByTestId("epic-board-card-story");
      const focusedRow = rows.find((r) => r.getAttribute("data-story-id") === "story-1")!;
      const otherRow = rows.find((r) => r.getAttribute("data-story-id") === "story-2")!;
      expect(focusedRow).toHaveAttribute("data-focused", "true");
      expect(otherRow).toHaveAttribute("data-focused", "false");
    });

    it("an epic/story param matching no loaded Epic renders exactly like the no-focus case", async () => {
      mockApi.getPage.mockResolvedValue(makePage([makeEpic({ id: "epic-1", title: "Unfocused Epic" })]));

      renderWithProviders(<RoadmapBoardPage />, {
        initialEntries: ["/roadmap/board?epic=does-not-exist&story=also-does-not-exist"],
      });

      await waitFor(() => expect(screen.getByText("Unfocused Epic")).toBeInTheDocument());
      expect(
        screen.getByText("Unfocused Epic").closest('[data-testid="epic-board-card"]')
      ).toHaveAttribute("data-focused", "false");
      expect(screen.queryByTestId("epic-board-card-stories")).not.toBeInTheDocument();
      expect(screen.getByTestId("roadmap-view-switcher-graph")).toBeDisabled();
    });
  });
});
