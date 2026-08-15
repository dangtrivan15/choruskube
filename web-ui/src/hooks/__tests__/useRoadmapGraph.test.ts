import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";
import type { RoadmapGraphSnapshot } from "@/lib/types";

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
import { useRoadmapGraph } from "@/hooks/useRoadmapGraph";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
};

function makeSnapshot(): RoadmapGraphSnapshot {
  return {
    epic: {
      id: "epic-1",
      title: "Add dark mode",
      description: "desc",
      motivation: null,
      status: "in_progress",
      stage: "in_progress",
      priority: "medium",
      targetDate: null,
      progress: { totalTasks: 2, doneTasks: 0 },
      softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
      repos: [],
      createdAt: "2026-04-01T00:00:00Z",
      updatedAt: "2026-04-01T00:00:00Z",
      readyItemCount: 0,
      milestone: null,
    },
    stories: [],
    tasks: [],
    dependencies: [],
    externalBlockers: [],
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("useRoadmapGraph", () => {
  it("fetches from /epics/{epicId}/graph using the ['epics', epicId, 'graph'] query key", async () => {
    mockApi.get.mockResolvedValue(makeSnapshot());
    const { wrapper, queryClient } = createTestHookWrapper();

    const { result } = renderHook(() => useRoadmapGraph("epic-1"), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockApi.get).toHaveBeenCalledWith("/epics/epic-1/graph");
    expect(queryClient.getQueryData(["epics", "epic-1", "graph"])).toEqual(makeSnapshot());
  });

  it("is disabled (does not fetch) while epicId is undefined", () => {
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useRoadmapGraph(undefined), { wrapper });

    expect(result.current.fetchStatus).toBe("idle");
    expect(mockApi.get).not.toHaveBeenCalled();
  });

  it("starts in a loading state before the fetch resolves", () => {
    mockApi.get.mockReturnValue(new Promise(() => {})); // never resolves
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useRoadmapGraph("epic-1"), { wrapper });

    expect(result.current.isLoading).toBe(true);
    expect(result.current.data).toBeUndefined();
  });

  it("surfaces a fetch error via isError", async () => {
    mockApi.get.mockRejectedValue(new Error("boom"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useRoadmapGraph("epic-1"), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});
