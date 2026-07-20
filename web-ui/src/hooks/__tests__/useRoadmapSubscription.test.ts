import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

// Mock @stomp/stompjs with a real class
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
      // Wire up activate to simulate connection
      this.activate = mockActivate.mockImplementation(() => {
        if (this.onConnect) {
          this.onConnect();
        }
      });
    }
  }

  return { Client: MockClient };
});

import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";

describe("useRoadmapSubscription", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("creates a STOMP client and subscribes to the org-free roadmap-items topic", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useRoadmapSubscription(), { wrapper });

    expect(mockActivate).toHaveBeenCalled();
    expect(mockSubscribe).toHaveBeenCalledWith(
      "/topic/roadmap-items",
      expect.any(Function)
    );
  });

  it("deactivates the client on unmount", () => {
    const { wrapper } = createTestHookWrapper();

    const { unmount } = renderHook(() => useRoadmapSubscription(), {
      wrapper,
    });

    unmount();

    expect(mockDeactivate).toHaveBeenCalled();
  });

  it("invalidates epics, stories, and tasks queries on message", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderHook(() => useRoadmapSubscription(), { wrapper });

    // Get the callback that was passed to subscribe and invoke it
    const subscribeCallback = mockSubscribe.mock.calls[0][1];
    subscribeCallback({ body: "{}" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories"] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["tasks"] });
  });

  it("regression: a dependency_changed message invalidates a graph query via the ['epics'] prefix", () => {
    // The hook invalidates the literal ["epics"] key (not scoped to a specific
    // epic/graph query). TanStack Query's default `exact: false` matching means
    // that invalidation also matches any query whose key starts with "epics" —
    // including ["epics", epicId, "graph"] — without the hook needing to know
    // about the Roadmap Graph View's query key shape at all. This test seeds a
    // real graph query in the cache and asserts it gets marked invalid when a
    // dependency_changed event arrives, so a future refactor of this hook (e.g.
    // switching to more targeted invalidation) can't silently break that.
    const { wrapper, queryClient } = createTestHookWrapper();

    const graphQueryKey = ["epics", "epic-1", "graph"];
    queryClient.setQueryData(graphQueryKey, { epic: { id: "epic-1" } });

    renderHook(() => useRoadmapSubscription(), { wrapper });

    const subscribeCallback = mockSubscribe.mock.calls[0][1];
    subscribeCallback({
      body: JSON.stringify({ itemType: "dependency_changed", itemId: "dep-1", status: "created" }),
    });

    expect(queryClient.getQueryState(graphQueryKey)?.isInvalidated).toBe(true);
  });
});
