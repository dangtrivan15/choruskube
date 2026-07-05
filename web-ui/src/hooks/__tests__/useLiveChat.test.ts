import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

// Mock @stomp/stompjs
vi.mock("@stomp/stompjs", () => {
  class MockClient {
    subscribe = vi.fn();
    activate = vi.fn();
    deactivate = vi.fn();
    constructor() {
      this.activate = vi.fn();
    }
  }
  return { Client: MockClient };
});

// Mock api module
vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

// Mock toast-messages
vi.mock("@/lib/toast-messages", () => ({
  showMutationToast: vi.fn(() => ({
    id: "test-toast",
    timestamp: Date.now(),
    message: "test",
    variant: "info" as const,
  })),
}));

import {
  useLiveChatSession,
  useCompleteLiveChat,
  useLiveChatMessages,
} from "@/hooks/useLiveChat";

describe("useLiveChatSession", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("is disabled when nodeExecId is null", () => {
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(
      () => useLiveChatSession("run-1", null),
      { wrapper }
    );

    expect(result.current.data).toBeUndefined();
    expect(result.current.isLoading).toBe(false);
    expect(result.current.fetchStatus).toBe("idle");
  });
});

describe("useCompleteLiveChat", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("invalidates live-chat query on success", async () => {
    const { api } = await import("@/lib/api");
    vi.mocked(api.post).mockResolvedValue({
      id: "session-1",
      nodeExecutionId: "exec-1",
      workflowRunId: "run-1",
      status: "completed",
      createdAt: "2026-04-08T00:00:00Z",
    });

    const { queryClient, wrapper } = createTestHookWrapper();

    // Pre-populate the cache with an active session
    queryClient.setQueryData(["live-chat", "run-1", "exec-1"], {
      id: "session-1",
      status: "active",
    });

    // Verify the data is in the cache
    expect(
      queryClient.getQueryData(["live-chat", "run-1", "exec-1"])
    ).toBeDefined();

    // Spy on invalidateQueries to verify it's called (not removeQueries)
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    const { result } = renderHook(() => useCompleteLiveChat("run-1"), {
      wrapper,
    });

    // Trigger the mutation
    act(() => {
      result.current.mutate({
        nodeExecId: "exec-1",
        transcript: "test transcript",
      });
    });

    // Wait for the mutation to succeed and onSuccess to run
    await waitFor(() => {
      expect(result.current.isSuccess).toBe(true);
    });

    // Verify invalidateQueries was called with the correct key
    // (invalidateQueries marks query stale; without active observers the
    // garbage-collected cache entry may be removed, so we verify the call
    // instead of the cache state)
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ["live-chat", "run-1", "exec-1"],
    });
  });
});

describe("useLiveChatMessages", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns empty messages initially when sessionId is null", () => {
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(
      () => useLiveChatMessages("run-1", null),
      { wrapper }
    );

    expect(result.current.messages).toEqual([]);
  });

  it("addMessage adds a message to the list", () => {
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(
      () => useLiveChatMessages("run-1", null),
      { wrapper }
    );

    act(() => {
      result.current.addMessage("user", "Hello!");
    });

    expect(result.current.messages).toHaveLength(1);
    expect(result.current.messages[0].role).toBe("user");
    expect(result.current.messages[0].content).toBe("Hello!");
  });

  it("clearMessages resets messages to empty", () => {
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(
      () => useLiveChatMessages("run-1", null),
      { wrapper }
    );

    // Add a message first
    act(() => {
      result.current.addMessage("assistant", "Hi there!");
    });

    expect(result.current.messages).toHaveLength(1);

    // Clear messages
    act(() => {
      result.current.clearMessages();
    });

    expect(result.current.messages).toEqual([]);
  });
});
