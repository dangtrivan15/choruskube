import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/lib/api", () => {
  class MockApiError extends Error {
    status: number;
    body: unknown;
    constructor(status: number, body: unknown) {
      super(`API error ${status}`);
      this.status = status;
      this.body = body;
    }
  }
  return {
    api: {
      get: vi.fn(),
      post: vi.fn(),
    },
    ApiError: MockApiError,
    apiErrorMessage: (err: unknown, fallback: string) =>
      err instanceof MockApiError && typeof err.body === "string" ? err.body : fallback,
  };
});

vi.mock("@/lib/toast-messages", () => ({
  showMutationToast: vi.fn((message: string, variant: string) => ({
    id: "mock-toast-id",
    timestamp: Date.now(),
    message,
    variant,
  })),
}));

import { api, ApiError } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import {
  useStorySpec,
  useStorySpecVersions,
  useStorySpecVersion,
  useStoryConflicts,
  useDesignStory,
  useReviseStory,
} from "@/hooks/useStorySpec";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
};

const spec = {
  storyId: "story-1",
  version: 2,
  content: "# Spec",
  approvedAt: "2026-11-01T00:00:00Z",
  runId: "run-9",
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe("useStorySpec", () => {
  it("fetches the current spec under the Story's key", async () => {
    mockApi.get.mockResolvedValueOnce(spec);
    const { wrapper, queryClient } = createTestHookWrapper();

    const { result } = renderHook(() => useStorySpec("story-1"), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(spec);
    expect(mockApi.get).toHaveBeenCalledWith("/stories/story-1/spec");
    expect(queryClient.getQueryData(["stories", "story-1", "spec"])).toEqual(spec);
  });

  it("reads a 404 as no spec rather than an error", async () => {
    mockApi.get.mockRejectedValueOnce(new ApiError(404, "Story has no spec"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useStorySpec("story-1"), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it("surfaces other failures as errors", async () => {
    mockApi.get.mockRejectedValueOnce(new ApiError(500, "boom"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useStorySpec("story-1"), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("does not fetch without a Story id", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useStorySpec(undefined), { wrapper });

    expect(mockApi.get).not.toHaveBeenCalled();
  });
});

describe("useStorySpecVersions", () => {
  it("lists versions under the spec's versions key", async () => {
    const versions = [{ version: 1, approvedAt: "2026-10-01T00:00:00Z", runId: "run-1" }];
    mockApi.get.mockResolvedValueOnce(versions);
    const { wrapper, queryClient } = createTestHookWrapper();

    const { result } = renderHook(() => useStorySpecVersions("story-1"), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith("/stories/story-1/spec/versions");
    expect(queryClient.getQueryData(["stories", "story-1", "spec", "versions"])).toEqual(versions);
  });
});

describe("useStorySpecVersion", () => {
  it("fetches one version under its own key", async () => {
    mockApi.get.mockResolvedValueOnce({ ...spec, version: 1 });
    const { wrapper, queryClient } = createTestHookWrapper();

    const { result } = renderHook(() => useStorySpecVersion("story-1", 1), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith("/stories/story-1/spec/versions/1");
    expect(queryClient.getQueryData(["stories", "story-1", "spec", "versions", 1])).toMatchObject({
      version: 1,
    });
  });

  it("does not fetch until a version is chosen", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useStorySpecVersion("story-1", null), { wrapper });

    expect(mockApi.get).not.toHaveBeenCalled();
  });
});

describe("useStoryConflicts", () => {
  it("lists the Story's open conflicts", async () => {
    mockApi.get.mockResolvedValueOnce([]);
    const { wrapper, queryClient } = createTestHookWrapper();

    const { result } = renderHook(() => useStoryConflicts("story-1"), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.get).toHaveBeenCalledWith("/stories/story-1/conflicts");
    expect(queryClient.getQueryData(["stories", "story-1", "conflicts"])).toEqual([]);
  });
});

describe("useDesignStory", () => {
  it("starts a design, invalidates the Story, and toasts a link to the run", async () => {
    mockApi.post.mockResolvedValueOnce({ id: "run-7" });
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useDesignStory(), { wrapper });
    result.current.mutate("story-1");

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.post).toHaveBeenCalledWith("/stories/story-1/design");
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories", "story-1"] });
    expect(showMutationToast).toHaveBeenCalledWith("Story design started", "success", "/runs/run-7");
  });

  it("toasts the server's reason when the design is refused", async () => {
    mockApi.post.mockRejectedValueOnce(new ApiError(409, "Story story-1 already has an open design run"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useDesignStory(), { wrapper });
    result.current.mutate("story-1");

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(showMutationToast).toHaveBeenCalledWith("Story story-1 already has an open design run", "error");
  });
});

describe("useReviseStory", () => {
  it("posts the guidance, invalidates the Story, and toasts a link to the run", async () => {
    mockApi.post.mockResolvedValueOnce({ id: "run-8" });
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useReviseStory(), { wrapper });
    result.current.mutate({ storyId: "story-1", guidance: "Split the API section" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.post).toHaveBeenCalledWith("/stories/story-1/revise", {
      guidance: "Split the API section",
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories", "story-1"] });
    expect(showMutationToast).toHaveBeenCalledWith("Story revision started", "success", "/runs/run-8");
  });

  it("toasts the server's reason when the revision is refused", async () => {
    mockApi.post.mockRejectedValueOnce(new ApiError(409, "Story story-1 has no approved spec to revise"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useReviseStory(), { wrapper });
    result.current.mutate({ storyId: "story-1", guidance: "x" });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(showMutationToast).toHaveBeenCalledWith("Story story-1 has no approved spec to revise", "error");
  });
});
