import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

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
import { useUpdateEpicStage, EPIC_BOARD_PAGINATION } from "@/hooks/useEpics";
import type { EpicResponse, PageResponse } from "@/lib/types";

const mockApi = api as unknown as {
  patch: ReturnType<typeof vi.fn>;
};

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Add a dark theme",
    motivation: null,
    status: "backlog",
    stage: "backlog",
    progress: { totalTasks: 0, doneTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    ...overrides,
  };
}

function boardQueryKey(readyOnly = false) {
  return ["epics", { title: undefined, pagination: EPIC_BOARD_PAGINATION, readyOnly }] as const;
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

describe("useUpdateEpicStage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("calls PATCH /epics/{id}/stage with the expected body", async () => {
    const epic = makeEpic();
    mockApi.patch.mockResolvedValueOnce({ ...epic, stage: "in_progress" });
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateEpicStage(false), { wrapper });

    result.current.mutate({ id: "epic-1", stage: "in_progress" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.patch).toHaveBeenCalledWith("/epics/epic-1/stage", {
      stage: "in_progress",
    });
  });

  it("optimistically updates the board's cached epics before the network call resolves", async () => {
    let resolvePatch: (value: EpicResponse) => void;
    mockApi.patch.mockReturnValueOnce(
      new Promise<EpicResponse>((resolve) => {
        resolvePatch = resolve;
      })
    );

    const { wrapper, queryClient } = createTestHookWrapper();
    // This test seeds cache data with no active `useEpics` query observer, so the
    // wrapper's default `gcTime: 0` would garbage-collect it before `onMutate` ever
    // reads it — disable GC for the epics query prefix.
    queryClient.setQueryDefaults(["epics"], { gcTime: Infinity });
    const epic = makeEpic({ stage: "backlog" });
    queryClient.setQueryData(boardQueryKey(), makePage([epic]));

    const { result } = renderHook(() => useUpdateEpicStage(false), { wrapper });

    act(() => {
      result.current.mutate({ id: "epic-1", stage: "in_progress" });
    });

    // Optimistic update is visible immediately, before the network call resolves.
    await waitFor(() => {
      const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey());
      expect(cached?.content[0].stage).toBe("in_progress");
    });

    resolvePatch!({ ...epic, stage: "in_progress" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("rolls back the cached epics on error", async () => {
    mockApi.patch.mockRejectedValueOnce(new Error("boom"));

    const { wrapper, queryClient } = createTestHookWrapper();
    queryClient.setQueryDefaults(["epics"], { gcTime: Infinity });
    const epic = makeEpic({ stage: "backlog" });
    queryClient.setQueryData(boardQueryKey(), makePage([epic]));

    const { result } = renderHook(() => useUpdateEpicStage(false), { wrapper });

    act(() => {
      result.current.mutate({ id: "epic-1", stage: "in_progress" });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));

    const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey());
    expect(cached?.content[0].stage).toBe("backlog");
  });

  it("with the 'Ready to start' toggle on, optimistically updates and rolls back the readyOnly:true cache entry, not the unfiltered one", async () => {
    // Regression guard for boardEpicsQueryKey's readyOnly parameterization: this hook must
    // target the cache entry matching the board's *current* toggle state, not always the
    // unfiltered (readyOnly: false) one.
    let rejectPatch: (err: Error) => void;
    mockApi.patch.mockReturnValueOnce(
      new Promise<EpicResponse>((_resolve, reject) => {
        rejectPatch = reject;
      })
    );

    const { wrapper, queryClient } = createTestHookWrapper();
    queryClient.setQueryDefaults(["epics"], { gcTime: Infinity });
    const readyEpic = makeEpic({ stage: "backlog" });
    const unfilteredEpic = makeEpic({ stage: "backlog" });
    queryClient.setQueryData(boardQueryKey(true), makePage([readyEpic]));
    queryClient.setQueryData(boardQueryKey(false), makePage([unfilteredEpic]));

    const { result } = renderHook(() => useUpdateEpicStage(true), { wrapper });

    act(() => {
      result.current.mutate({ id: "epic-1", stage: "in_progress" });
    });

    await waitFor(() => {
      const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey(true));
      expect(cached?.content[0].stage).toBe("in_progress");
    });
    // The unfiltered (readyOnly: false) entry must be untouched.
    expect(
      queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey(false))?.content[0].stage
    ).toBe("backlog");

    act(() => {
      rejectPatch!(new Error("boom"));
    });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(
      queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey(true))?.content[0].stage
    ).toBe("backlog");
  });

  it("rolling back a failed mutation does not clobber a different epic's concurrent optimistic move", async () => {
    // Reproduces the interleaving that a whole-page rollback would get wrong: epic-1's
    // move (A) is still in flight when epic-2's move (B) starts and applies its own
    // optimistic update on top of A's. If A then fails, its rollback must restore only
    // epic-1's stage — not the entire page snapshot A captured before B ever ran, which
    // would silently erase B's already-applied move until the next refetch.
    let rejectA: (err: Error) => void;
    mockApi.patch.mockImplementationOnce(
      () =>
        new Promise<EpicResponse>((_resolve, reject) => {
          rejectA = reject;
        })
    );
    mockApi.patch.mockResolvedValueOnce(makeEpic({ id: "epic-2", stage: "rolled_out" }));

    const { wrapper, queryClient } = createTestHookWrapper();
    queryClient.setQueryDefaults(["epics"], { gcTime: Infinity });
    const epic1 = makeEpic({ id: "epic-1", stage: "backlog" });
    const epic2 = makeEpic({ id: "epic-2", stage: "backlog" });
    queryClient.setQueryData(boardQueryKey(), makePage([epic1, epic2]));

    const { result: resultA } = renderHook(() => useUpdateEpicStage(false), { wrapper });
    const { result: resultB } = renderHook(() => useUpdateEpicStage(false), { wrapper });

    // A: epic-1 -> in_progress (left pending).
    act(() => {
      resultA.current.mutate({ id: "epic-1", stage: "in_progress" });
    });
    await waitFor(() => {
      const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey());
      expect(cached?.content.find((e) => e.id === "epic-1")?.stage).toBe("in_progress");
    });

    // B: epic-2 -> rolled_out, starts and resolves while A is still pending.
    act(() => {
      resultB.current.mutate({ id: "epic-2", stage: "rolled_out" });
    });
    await waitFor(() => expect(resultB.current.isSuccess).toBe(true));
    expect(
      queryClient
        .getQueryData<PageResponse<EpicResponse>>(boardQueryKey())
        ?.content.find((e) => e.id === "epic-2")?.stage
    ).toBe("rolled_out");

    // A now fails — its rollback must restore only epic-1, leaving B's already-settled
    // move on epic-2 intact.
    act(() => {
      rejectA!(new Error("boom"));
    });
    await waitFor(() => expect(resultA.current.isError).toBe(true));

    const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey());
    expect(cached?.content.find((e) => e.id === "epic-1")?.stage).toBe("backlog");
    expect(cached?.content.find((e) => e.id === "epic-2")?.stage).toBe("rolled_out");
  });

  it("a stale failure on a re-dragged epic does not clobber its later successful move", async () => {
    // The same epic can be dragged twice before the first PATCH settles: epic-1 moves
    // backlog -> in_progress (A, left pending), then immediately in_progress -> rolled_out
    // (B, which resolves first). If A then fails, its rollback must not blindly restore its
    // pre-A snapshot ("backlog") over B's already-succeeded "rolled_out" — that would silently
    // erase a confirmed server-side move using a stale snapshot captured before B ever ran.
    let rejectA: (err: Error) => void;
    mockApi.patch.mockImplementationOnce(
      () =>
        new Promise<EpicResponse>((_resolve, reject) => {
          rejectA = reject;
        })
    );
    mockApi.patch.mockResolvedValueOnce(makeEpic({ id: "epic-1", stage: "rolled_out" }));

    const { wrapper, queryClient } = createTestHookWrapper();
    queryClient.setQueryDefaults(["epics"], { gcTime: Infinity });
    const epic1 = makeEpic({ id: "epic-1", stage: "backlog" });
    queryClient.setQueryData(boardQueryKey(), makePage([epic1]));

    const { result: resultA } = renderHook(() => useUpdateEpicStage(false), { wrapper });
    const { result: resultB } = renderHook(() => useUpdateEpicStage(false), { wrapper });

    // A: epic-1 backlog -> in_progress (left pending).
    act(() => {
      resultA.current.mutate({ id: "epic-1", stage: "in_progress" });
    });
    await waitFor(() => {
      const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey());
      expect(cached?.content.find((e) => e.id === "epic-1")?.stage).toBe("in_progress");
    });

    // B: epic-1 in_progress -> rolled_out, starts and resolves while A is still pending.
    act(() => {
      resultB.current.mutate({ id: "epic-1", stage: "rolled_out" });
    });
    await waitFor(() => expect(resultB.current.isSuccess).toBe(true));
    expect(
      queryClient
        .getQueryData<PageResponse<EpicResponse>>(boardQueryKey())
        ?.content.find((e) => e.id === "epic-1")?.stage
    ).toBe("rolled_out");

    // A now fails — its rollback targeted "in_progress" (what it applied), which the cache no
    // longer holds, so it must be a no-op: epic-1 stays at B's confirmed "rolled_out".
    act(() => {
      rejectA!(new Error("boom"));
    });
    await waitFor(() => expect(resultA.current.isError).toBe(true));

    const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey());
    expect(cached?.content.find((e) => e.id === "epic-1")?.stage).toBe("rolled_out");
  });
});
