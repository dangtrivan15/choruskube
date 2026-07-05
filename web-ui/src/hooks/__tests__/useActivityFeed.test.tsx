import { describe, it, expect } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { type ReactNode } from "react";
import { ActivityFeedProvider, useActivityFeed } from "@/hooks/useActivityFeed";
import type { ActivityFeedEntry } from "@/lib/types";

function wrapper({ children }: { children: ReactNode }) {
  return <ActivityFeedProvider>{children}</ActivityFeedProvider>;
}

function makeEntry(overrides: Partial<ActivityFeedEntry> = {}): ActivityFeedEntry {
  return {
    id: `test-${Date.now()}-${Math.random()}`,
    timestamp: Date.now(),
    message: "Test message",
    variant: "info",
    ...overrides,
  };
}

describe("useActivityFeed", () => {
  it("throws if used outside provider", () => {
    expect(() => {
      renderHook(() => useActivityFeed());
    }).toThrow("useActivityFeed must be used within an ActivityFeedProvider");
  });

  it("starts with empty entries and zero unread", () => {
    const { result } = renderHook(() => useActivityFeed(), { wrapper });
    expect(result.current.entries).toEqual([]);
    expect(result.current.unreadCount).toBe(0);
  });

  it("adds entries to the front of the list", () => {
    const { result } = renderHook(() => useActivityFeed(), { wrapper });

    const entry1 = makeEntry({ id: "e1", message: "First" });
    const entry2 = makeEntry({ id: "e2", message: "Second" });

    act(() => result.current.addEntry(entry1));
    act(() => result.current.addEntry(entry2));

    expect(result.current.entries).toHaveLength(2);
    expect(result.current.entries[0].message).toBe("Second");
    expect(result.current.entries[1].message).toBe("First");
  });

  it("increments unread count for new entries", () => {
    const { result } = renderHook(() => useActivityFeed(), { wrapper });

    act(() => {
      result.current.addEntry(makeEntry({ timestamp: Date.now() + 100 }));
    });

    expect(result.current.unreadCount).toBe(1);
  });

  it("marks all as read", () => {
    const { result } = renderHook(() => useActivityFeed(), { wrapper });

    act(() => {
      result.current.addEntry(makeEntry({ timestamp: Date.now() + 100 }));
      result.current.addEntry(makeEntry({ timestamp: Date.now() + 200 }));
    });

    expect(result.current.unreadCount).toBe(2);

    act(() => result.current.markAllRead());
    expect(result.current.unreadCount).toBe(0);
  });

  it("clears all entries", () => {
    const { result } = renderHook(() => useActivityFeed(), { wrapper });

    act(() => {
      result.current.addEntry(makeEntry());
      result.current.addEntry(makeEntry());
    });

    expect(result.current.entries).toHaveLength(2);

    act(() => result.current.clearAll());
    expect(result.current.entries).toHaveLength(0);
    expect(result.current.unreadCount).toBe(0);
  });

  it("caps entries at 100", () => {
    const { result } = renderHook(() => useActivityFeed(), { wrapper });

    act(() => {
      for (let i = 0; i < 110; i++) {
        result.current.addEntry(makeEntry({ id: `entry-${i}`, timestamp: Date.now() + i }));
      }
    });

    expect(result.current.entries).toHaveLength(100);
    // Most recent entry should be at index 0
    expect(result.current.entries[0].id).toBe("entry-109");
  });
});
