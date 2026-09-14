import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";
import type { StoryResponse, StoryUpdateRequest } from "@/lib/types";

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

vi.mock("@/lib/toast-messages", () => ({
  showMutationToast: vi.fn((message: string, variant: string) => ({
    id: "mock-toast-id",
    timestamp: Date.now(),
    message,
    variant,
  })),
}));

import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useUpdateStory } from "@/hooks/useStories";

const mockApi = api as unknown as {
  put: ReturnType<typeof vi.fn>;
};

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "New title",
    description: "New desc",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    readyTaskCount: null,
    progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

const body: StoryUpdateRequest = { title: "New title", description: "New desc" };

beforeEach(() => {
  vi.clearAllMocks();
});

describe("useUpdateStory", () => {
  it("issues PUT /stories/{id} with the given body", async () => {
    mockApi.put.mockResolvedValue(makeStory());
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateStory(), { wrapper });
    result.current.mutate({ id: "story-1", body });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.put).toHaveBeenCalledWith("/stories/story-1", body);
  });

  it("invalidates the stories and epics query keys on success", async () => {
    mockApi.put.mockResolvedValue(makeStory());
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useUpdateStory(), { wrapper });
    result.current.mutate({ id: "story-1", body });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories", "story-1"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics"] });
  });

  it("shows a success toast on success", async () => {
    mockApi.put.mockResolvedValue(makeStory());
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateStory(), { wrapper });
    result.current.mutate({ id: "story-1", body });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(showMutationToast).toHaveBeenCalledWith("Story updated", "success");
  });

  it("shows a failure toast and does not invalidate on error", async () => {
    mockApi.put.mockRejectedValue(new Error("conflict"));
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useUpdateStory(), { wrapper });
    result.current.mutate({ id: "story-1", body });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(showMutationToast).toHaveBeenCalledWith("Failed to update story", "error");
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
