import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import type { IMessage } from "@stomp/stompjs";
import { createTestHookWrapper } from "@/__tests__/test-utils";

const mockUseStompSubscription = vi.fn();
vi.mock("@/hooks/useStompSubscription", () => ({
  useStompSubscription: (topic: string | null, onMessage: (message: IMessage) => void) =>
    mockUseStompSubscription(topic, onMessage),
}));

import { useStorySubscription } from "@/hooks/useStorySubscription";

function deliver(body: string) {
  const onMessage = mockUseStompSubscription.mock.lastCall?.[1] as (message: IMessage) => void;
  onMessage({ body } as IMessage);
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("useStorySubscription", () => {
  it("subscribes to the roadmap feed for a Story", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useStorySubscription("story-1"), { wrapper });

    expect(mockUseStompSubscription).toHaveBeenCalledWith("/topic/roadmap-items", expect.any(Function));
  });

  it("does not subscribe without a Story", () => {
    const { wrapper } = createTestHookWrapper();

    renderHook(() => useStorySubscription(null), { wrapper });

    expect(mockUseStompSubscription).toHaveBeenCalledWith(null, expect.any(Function));
  });

  it("refreshes the Story's queries on its own event only", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");
    renderHook(() => useStorySubscription("story-1"), { wrapper });

    deliver(JSON.stringify({ itemType: "story_changed", itemId: "story-2", status: "backlog" }));
    deliver(JSON.stringify({ itemType: "task_changed", itemId: "story-1", status: "done" }));
    expect(invalidateSpy).not.toHaveBeenCalled();

    deliver(JSON.stringify({ itemType: "story_changed", itemId: "story-1", status: "backlog" }));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories", "story-1"] });
  });

  it("refreshes on an unreadable event rather than risk a stale page", () => {
    const { wrapper, queryClient } = createTestHookWrapper();
    const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");
    renderHook(() => useStorySubscription("story-1"), { wrapper });

    deliver("not json");

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["stories", "story-1"] });
  });
});
