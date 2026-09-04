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

import { useRunSubscription } from "@/hooks/useRunSubscription";

describe("useRunSubscription", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("creates a STOMP client and subscribes to the run topic", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useRunSubscription("run-123"), { wrapper });

    expect(mockActivate).toHaveBeenCalled();
    expect(mockSubscribe).toHaveBeenCalledWith(
      "/topic/runs/run-123",
      expect.any(Function)
    );
  });

  it("does not create a client when runId is undefined", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useRunSubscription(undefined), { wrapper });

    expect(mockActivate).not.toHaveBeenCalled();
  });

  it("deactivates the client on unmount", () => {
    const { wrapper } = createTestHookWrapper();

    const { unmount } = renderHook(() => useRunSubscription("run-123"), {
      wrapper,
    });

    unmount();

    expect(mockDeactivate).toHaveBeenCalled();
  });

  it("reconnects when runId changes", () => {
    const { wrapper } = createTestHookWrapper();

    const { rerender } = renderHook(
      ({ runId }) => useRunSubscription(runId),
      {
        wrapper,
        initialProps: { runId: "run-1" as string | undefined },
      }
    );

    expect(mockActivate).toHaveBeenCalledTimes(1);
    expect(mockSubscribe).toHaveBeenCalledWith(
      "/topic/runs/run-1",
      expect.any(Function)
    );

    // Change runId triggers cleanup + new subscription
    rerender({ runId: "run-2" });

    expect(mockDeactivate).toHaveBeenCalledTimes(1);
    expect(mockActivate).toHaveBeenCalledTimes(2);
    expect(mockSubscribe).toHaveBeenCalledWith(
      "/topic/runs/run-2",
      expect.any(Function)
    );
  });

});
