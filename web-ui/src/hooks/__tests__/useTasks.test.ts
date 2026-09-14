import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";
import type { TaskResponse } from "@/lib/types";

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
import { useUpdateTask } from "@/hooks/useTasks";

const mockApi = api as unknown as {
  put: ReturnType<typeof vi.fn>;
};

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "New title",
    description: "New desc",
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
    priority: "medium",
    ...overrides,
  };
}

const body = { title: "New title", description: "New desc" };

beforeEach(() => {
  vi.clearAllMocks();
});

describe("useUpdateTask", () => {
  it("issues PUT /tasks/{id} with a { title, description } body (no priority key)", async () => {
    mockApi.put.mockResolvedValue(makeTask());
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateTask(), { wrapper });
    result.current.mutate({ id: "task-1", body });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.put).toHaveBeenCalledWith("/tasks/task-1", body);
    expect(mockApi.put.mock.calls[0][1]).not.toHaveProperty("priority");
  });

  it("invalidates task, story, and epic query keys on success", async () => {
    mockApi.put.mockResolvedValue(makeTask({ id: "task-1", storyId: "story-1" }));
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useUpdateTask(), { wrapper });
    result.current.mutate({ id: "task-1", body });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["tasks"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["tasks", "task-1"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories", "story-1", "tasks"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics"] });
  });

  it("shows a success toast on success", async () => {
    mockApi.put.mockResolvedValue(makeTask());
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateTask(), { wrapper });
    result.current.mutate({ id: "task-1", body });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(showMutationToast).toHaveBeenCalledWith("Task updated", "success");
  });

  it("shows a failure toast and does not invalidate on error", async () => {
    mockApi.put.mockRejectedValue(new Error("conflict"));
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useUpdateTask(), { wrapper });
    result.current.mutate({ id: "task-1", body });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(showMutationToast).toHaveBeenCalledWith("Failed to update task", "error");
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
