import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
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
import { useUpdateEpicPriority } from "@/hooks/useEpics";
import type { EpicResponse } from "@/lib/types";

const mockApi = api as unknown as {
  patch: ReturnType<typeof vi.fn>;
};

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Add a dark theme",
    motivation: null,
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    milestone: null,
    ...overrides,
  };
}

describe("useUpdateEpicPriority", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("calls PATCH /epics/{id}/priority with the expected body", async () => {
    mockApi.patch.mockResolvedValueOnce(makeEpic({ priority: "high" }));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateEpicPriority(), { wrapper });

    result.current.mutate({ id: "epic-1", priority: "high" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.patch).toHaveBeenCalledWith("/epics/epic-1/priority", {
      priority: "high",
    });
  });

  it("invalidates the ['epics'] query key on success", async () => {
    mockApi.patch.mockResolvedValueOnce(makeEpic({ priority: "low" }));
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useUpdateEpicPriority(), { wrapper });

    result.current.mutate({ id: "epic-1", priority: "low" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics"] });
  });
});
