import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";
import type { CreateDependencyRequest, DependencyEdgeResponse } from "@/lib/types";

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
import { useCreateDependency, useDeleteDependency } from "@/hooks/useDependencies";

const mockApi = api as unknown as {
  post: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

const edge: DependencyEdgeResponse = {
  id: "dep-1",
  blockingItemType: "task",
  blockingItemId: "task-1",
  blockedItemType: "task",
  blockedItemId: "task-2",
  createdAt: "2026-04-01T00:00:00Z",
};

const createRequest: CreateDependencyRequest = {
  blockingItemType: "task",
  blockingItemId: "task-1",
  blockedItemType: "task",
  blockedItemId: "task-2",
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe("useCreateDependency", () => {
  it("invalidates the epic's graph query on success", async () => {
    mockApi.post.mockResolvedValue(edge);
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useCreateDependency("epic-1"), { wrapper });
    result.current.mutate(createRequest);

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockApi.post).toHaveBeenCalledWith("/dependencies", createRequest);
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics", "epic-1", "graph"] });
  });

  it("does not invalidate the graph query when the mutation fails", async () => {
    mockApi.post.mockRejectedValue(new Error("conflict"));
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useCreateDependency("epic-1"), { wrapper });
    result.current.mutate(createRequest);

    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});

describe("useDeleteDependency", () => {
  it("invalidates the epic's graph query on success", async () => {
    mockApi.delete.mockResolvedValue(undefined);
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useDeleteDependency("epic-1"), { wrapper });
    result.current.mutate("dep-1");

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockApi.delete).toHaveBeenCalledWith("/dependencies/dep-1");
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics", "epic-1", "graph"] });
  });

  it("does not invalidate the graph query when the mutation fails", async () => {
    mockApi.delete.mockRejectedValue(new Error("forbidden"));
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useDeleteDependency("epic-1"), { wrapper });
    result.current.mutate("dep-1");

    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
