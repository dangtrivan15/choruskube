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
      this.activate = mockActivate.mockImplementation(() => {
        if (this.onConnect) {
          this.onConnect();
        }
      });
    }
  }

  return { Client: MockClient };
});

import { usePendingGatesSubscription } from "@/hooks/usePendingGatesSubscription";

describe("usePendingGatesSubscription", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("subscribes to the org-free pending-gates topic", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => usePendingGatesSubscription(), { wrapper });

    expect(mockActivate).toHaveBeenCalled();
    expect(mockSubscribe).toHaveBeenCalledWith(
      "/topic/pending-gates",
      expect.any(Function)
    );
  });

  it("deactivates the client on unmount", () => {
    const { wrapper } = createTestHookWrapper();

    const { unmount } = renderHook(() => usePendingGatesSubscription(), {
      wrapper,
    });

    unmount();

    expect(mockDeactivate).toHaveBeenCalled();
  });

  it("invalidates pending-gates queries on message", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

    renderHook(() => usePendingGatesSubscription(), { wrapper });

    const subscribeCallback = mockSubscribe.mock.calls[0][1];
    subscribeCallback({});

    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: ["pending-gates"],
    });
  });
});
