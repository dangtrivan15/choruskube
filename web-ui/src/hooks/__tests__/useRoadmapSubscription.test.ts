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
});
