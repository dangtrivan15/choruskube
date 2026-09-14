import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";
import type { AutopilotStatus } from "@/lib/types";

// Mock @stomp/stompjs with a real class — mirrors useRoadmapSubscription.test.ts.
const mockSubscribe = vi.fn();
const mockActivate = vi.fn();
const mockDeactivate = vi.fn();

vi.mock("@stomp/stompjs", () => {
  class MockClient {
    subscribe = mockSubscribe;
    activate = mockActivate;
    deactivate = mockDeactivate;
    private onConnect?: () => void;

    constructor(opts: { onConnect?: () => void }) {
      this.onConnect = opts.onConnect;
      this.activate = mockActivate.mockImplementation(() => {
        if (this.onConnect) {
          this.onConnect();
        }
      });
    }
  }

  return { Client: MockClient };
});

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
import { useAutopilotSubscription, useUpdateAutopilot } from "@/hooks/useAutopilot";

const mockApi = api as unknown as {
  patch: ReturnType<typeof vi.fn>;
};

const AUTOPILOT_QUERY_KEY = ["autopilot"] as const;

function makeStatus(overrides: Partial<AutopilotStatus> = {}): AutopilotStatus {
  return {
    engaged: true,
    maxParallel: 3,
    maxAwaitingHuman: 0,
    inFlight: 1,
    slots: 2,
    awaitingHuman: 0,
    nextUp: [],
    whyIdle: [],
    awaitingYou: [],
    needsAttention: [],
    heldTasks: [],
    consecutiveFailures: 0,
    disengagedReason: null,
    lastTickAt: null,
    ...overrides,
  };
}

function subscribeCallback() {
  return mockSubscribe.mock.calls[0][1] as (message: { body: string }) => void;
}

describe("useAutopilotSubscription", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("subscribes to the autopilot topic", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useAutopilotSubscription(), { wrapper });

    expect(mockActivate).toHaveBeenCalled();
    expect(mockSubscribe).toHaveBeenCalledWith("/topic/autopilot", expect.any(Function));
  });

  it("writes a well-formed status payload straight into the cache", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const setSpy = vi.spyOn(queryClient, "setQueryData");
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderHook(() => useAutopilotSubscription(), { wrapper });
    const status = makeStatus({ inFlight: 2 });
    subscribeCallback()({ body: JSON.stringify(status) });

    expect(setSpy).toHaveBeenCalledWith(AUTOPILOT_QUERY_KEY, status);
    expect(invalidateSpy).not.toHaveBeenCalled();
  });

  it("falls back to invalidateQueries on invalid JSON", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const setSpy = vi.spyOn(queryClient, "setQueryData");
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderHook(() => useAutopilotSubscription(), { wrapper });
    subscribeCallback()({ body: "{not json" });

    expect(setSpy).not.toHaveBeenCalled();
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: AUTOPILOT_QUERY_KEY });
  });

  it("falls back to invalidateQueries on well-formed JSON of the wrong shape, instead of caching it", () => {
    // Regression case this hardening exists for: JSON.parse succeeds, and the value satisfies
    // AutopilotStatus's compile-time-only type annotation, but it isn't actually one — e.g. an
    // unrelated event shape delivered to the same topic, or a differently-shaped response body.
    const { wrapper, queryClient } = createTestHookWrapper();
    const setSpy = vi.spyOn(queryClient, "setQueryData");
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderHook(() => useAutopilotSubscription(), { wrapper });
    subscribeCallback()({ body: JSON.stringify({ type: "some_other_event", id: "run-1" }) });

    expect(setSpy).not.toHaveBeenCalled();
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: AUTOPILOT_QUERY_KEY });
  });

  it("falls back to invalidateQueries when the payload is a JSON array, not an object", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const setSpy = vi.spyOn(queryClient, "setQueryData");
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderHook(() => useAutopilotSubscription(), { wrapper });
    subscribeCallback()({ body: JSON.stringify([1, 2, 3]) });

    expect(setSpy).not.toHaveBeenCalled();
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: AUTOPILOT_QUERY_KEY });
  });

  it("deactivates the client on unmount", () => {
    const { wrapper } = createTestHookWrapper();

    const { unmount } = renderHook(() => useAutopilotSubscription(), { wrapper });
    unmount();

    expect(mockDeactivate).toHaveBeenCalled();
  });

  it("writes a well-formed payload carrying maxAwaitingHuman/awaitingHuman straight into the cache", () => {
    // The type guard checks only engaged/maxParallel/nextUp, so it must accept a payload that
    // also carries the two new fields rather than rejecting it as an unexpected shape.
    const { wrapper, queryClient } = createTestHookWrapper();
    const setSpy = vi.spyOn(queryClient, "setQueryData");
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderHook(() => useAutopilotSubscription(), { wrapper });
    const status = makeStatus({ maxAwaitingHuman: 3, awaitingHuman: 2 });
    subscribeCallback()({ body: JSON.stringify(status) });

    expect(setSpy).toHaveBeenCalledWith(AUTOPILOT_QUERY_KEY, status);
    expect(invalidateSpy).not.toHaveBeenCalled();
  });

  it("tolerates a payload from an older API that omits maxAwaitingHuman/awaitingHuman", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const setSpy = vi.spyOn(queryClient, "setQueryData");
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");
    const status = makeStatus();
    delete (status as Partial<AutopilotStatus>).maxAwaitingHuman;
    delete (status as Partial<AutopilotStatus>).awaitingHuman;

    renderHook(() => useAutopilotSubscription(), { wrapper });
    subscribeCallback()({ body: JSON.stringify(status) });

    expect(setSpy).toHaveBeenCalledWith(AUTOPILOT_QUERY_KEY, status);
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});

describe("useUpdateAutopilot", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("sends maxAwaitingHuman in the PATCH body when provided", async () => {
    mockApi.patch.mockResolvedValueOnce(makeStatus({ maxAwaitingHuman: 5 }));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateAutopilot(), { wrapper });
    result.current.mutate({ maxAwaitingHuman: 5 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.patch).toHaveBeenCalledWith("/autopilot", { maxAwaitingHuman: 5 });
  });

  it("sends both fields when both are provided", async () => {
    mockApi.patch.mockResolvedValueOnce(makeStatus({ maxParallel: 4, maxAwaitingHuman: 2 }));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useUpdateAutopilot(), { wrapper });
    result.current.mutate({ maxParallel: 4, maxAwaitingHuman: 2 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.patch).toHaveBeenCalledWith("/autopilot", { maxParallel: 4, maxAwaitingHuman: 2 });
  });
});
