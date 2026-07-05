import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { ActivityFeedEntry } from "@/lib/types";

const MAX_ENTRIES = 100;

interface ActivityFeedContextValue {
  entries: ActivityFeedEntry[];
  unreadCount: number;
  addEntry: (entry: ActivityFeedEntry) => void;
  markAllRead: () => void;
  clearAll: () => void;
}

const ActivityFeedContext = createContext<ActivityFeedContextValue | null>(null);

export function ActivityFeedProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<ActivityFeedEntry[]>([]);
  const [lastReadTimestamp, setLastReadTimestamp] = useState<number>(Date.now());

  const addEntry = useCallback((entry: ActivityFeedEntry) => {
    setEntries((prev) => {
      const next = [entry, ...prev];
      return next.length > MAX_ENTRIES ? next.slice(0, MAX_ENTRIES) : next;
    });
  }, []);

  const markAllRead = useCallback(() => {
    setEntries((current) => {
      // Use the newest entry's timestamp (if any) to guarantee all entries are read,
      // even if timestamps are slightly ahead due to batching.
      const newest = current.length > 0 ? current[0].timestamp : Date.now();
      setLastReadTimestamp(Math.max(newest, Date.now()));
      return current;
    });
  }, []);

  const clearAll = useCallback(() => {
    setEntries([]);
    setLastReadTimestamp(Date.now());
  }, []);

  const unreadCount = useMemo(
    () => entries.filter((e) => e.timestamp > lastReadTimestamp).length,
    [entries, lastReadTimestamp],
  );

  const value = useMemo<ActivityFeedContextValue>(
    () => ({ entries, unreadCount, addEntry, markAllRead, clearAll }),
    [entries, unreadCount, addEntry, markAllRead, clearAll],
  );

  return (
    <ActivityFeedContext.Provider value={value}>
      {children}
    </ActivityFeedContext.Provider>
  );
}

export function useActivityFeed(): ActivityFeedContextValue {
  const ctx = useContext(ActivityFeedContext);
  if (!ctx) {
    throw new Error("useActivityFeed must be used within an ActivityFeedProvider");
  }
  return ctx;
}
