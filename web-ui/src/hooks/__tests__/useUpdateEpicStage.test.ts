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
    ...overrides,
  };
}

function boardQueryKey() {
  return ["epics", { title: undefined, pagination: EPIC_BOARD_PAGINATION }] as const;
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

    const { result } = renderHook(() => useUpdateEpicStage(), { wrapper });

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

    const { result } = renderHook(() => useUpdateEpicStage(), { wrapper });

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

    const { result } = renderHook(() => useUpdateEpicStage(), { wrapper });

    act(() => {
      result.current.mutate({ id: "epic-1", stage: "in_progress" });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));

    const cached = queryClient.getQueryData<PageResponse<EpicResponse>>(boardQueryKey());
    expect(cached?.content[0].stage).toBe("backlog");
  });
});
