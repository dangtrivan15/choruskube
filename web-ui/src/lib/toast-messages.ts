import { toast } from "sonner";
import type { RunEvent, RoadmapItemEvent, ActivityFeedEntry } from "./types";

// --- Toast variant types ---

type ToastVariant = "info" | "success" | "warning" | "error";

interface ToastConfig {
  message: string;
  variant: ToastVariant;
  duration?: number;
  actionUrl?: string;
}

// --- Deduplication ---

const recentFingerprints = new Map<string, number>();
const DEDUP_WINDOW_MS = 2000;

function isDuplicate(fingerprint: string): boolean {
  const now = Date.now();
  // Cleanup old entries
  for (const [key, ts] of recentFingerprints) {
    if (now - ts > DEDUP_WINDOW_MS) {
      recentFingerprints.delete(key);
    }
  }
  if (recentFingerprints.has(fingerprint)) {
    return true;
  }
  recentFingerprints.set(fingerprint, now);
  return false;
}

/** Exposed for testing */
export function clearDedupCache(): void {
  recentFingerprints.clear();
}

// --- Run event mapping ---

function mapRunEvent(event: RunEvent): ToastConfig | null {
  if (event.type === "run_status_changed" && event.status) {
    switch (event.status) {
      case "running":
        return {
          message: `Run started`,
          variant: "info",
          actionUrl: `/runs/${event.runId}`,
        };
      case "completed":
        return {
          message: `Run completed successfully`,
          variant: "success",
          actionUrl: `/runs/${event.runId}`,
        };
      case "failed":
        return {
          message: `Run failed`,
          variant: "error",
          actionUrl: `/runs/${event.runId}`,
        };
      case "cancelled":
        return {
          message: `Run cancelled`,
          variant: "warning",
          actionUrl: `/runs/${event.runId}`,
        };
      case "paused":
        return {
          message: `Run paused`,
          variant: "info",
          actionUrl: `/runs/${event.runId}`,
        };
      default:
        return null;
    }
  }

  if (event.type === "node_status_changed" && event.status) {
    switch (event.status) {
      case "awaiting_human":
        return {
          message: `A node is awaiting approval`,
          variant: "warning",
          actionUrl: `/approvals`,
        };
      case "failed":
        return {
          message: `A node failed`,
          variant: "error",
          actionUrl: `/runs/${event.runId}`,
        };
      default:
        // Don't toast for every node state transition (too noisy)
        return null;
    }
  }

  // node_logs_updated — too frequent, skip toast
  return null;
}

// --- Roadmap item event mapping ---

function mapRoadmapItemEvent(event: RoadmapItemEvent): ToastConfig | null {
  if (event.itemType === "run_status_changed") {
    switch (event.status) {
      case "completed":
        return {
          message: "Linked run completed",
          variant: "success",
          actionUrl: "/roadmap",
        };
      case "failed":
        return {
          message: "Linked run failed",
          variant: "error",
          actionUrl: "/roadmap",
        };
      case "cancelled":
        return {
          message: "Linked run cancelled",
          variant: "warning",
          actionUrl: "/roadmap",
        };
      default:
        return null;
    }
  }

  if (event.itemType === "dependency_changed") {
    switch (event.status) {
      case "created":
        return {
          message: "Blocking dependency added",
          variant: "info",
          actionUrl: "/roadmap",
        };
      case "deleted":
        return {
          message: "Blocking dependency removed",
          variant: "info",
          actionUrl: "/roadmap",
        };
      default:
        return null;
    }
  }

  // epic_changed / story_changed / task_changed
  switch (event.status) {
    case "backlog":
      return {
        message: "Roadmap item updated",
        variant: "info",
        actionUrl: "/roadmap",
      };
    case "in_progress":
      return {
        message: "Roadmap item started",
        variant: "info",
        actionUrl: "/roadmap",
      };
    case "done":
      return {
        message: "Roadmap item completed",
        variant: "success",
        actionUrl: "/roadmap",
      };
    case "deleted":
      return {
        message: "Roadmap item deleted",
        variant: "info",
      };
    default:
      return null;
  }
}

// --- Type guards ---
// Discriminate by field presence: RunEvent always has `runId`,
// RoadmapItemEvent always has `itemId`.

function isRunEvent(event: RunEvent | RoadmapItemEvent): event is RunEvent {
  return "runId" in event;
}

function isRoadmapItemEvent(event: RunEvent | RoadmapItemEvent): event is RoadmapItemEvent {
  return "itemId" in event;
}

// --- Public API ---

/** Determine the fingerprint for deduplication */
function fingerprint(event: RunEvent | RoadmapItemEvent): string {
  if (isRunEvent(event)) {
    return `run:${event.type}:${event.runId}:${event.nodeExecutionId ?? ""}:${event.status ?? ""}`;
  }
  if (isRoadmapItemEvent(event)) {
    return `roadmap:${event.itemType}:${event.itemId ?? ""}:${event.status}`;
  }
  return `unknown:${Date.now()}`;
}

export function mapEventToToast(
  event: RunEvent | RoadmapItemEvent,
): ToastConfig | null {
  if (isRunEvent(event)) {
    return mapRunEvent(event);
  }
  if (isRoadmapItemEvent(event)) {
    return mapRoadmapItemEvent(event);
  }
  return null;
}

/**
 * Show a toast and return an ActivityFeedEntry (or null if the event is not toastable
 * or is a duplicate within the dedup window).
 */
export function showEventToast(
  event: RunEvent | RoadmapItemEvent,
): ActivityFeedEntry | null {
  const config = mapEventToToast(event);
  if (!config) return null;

  const fp = fingerprint(event);
  if (isDuplicate(fp)) return null;

  const duration = config.duration ?? 4000;

  // Pass `id: fp` so Sonner deduplicates at the UI level — if two hooks
  // race past the fingerprint Map check, Sonner updates the existing toast
  // instead of creating a second one.
  switch (config.variant) {
    case "success":
      toast.success(config.message, { id: fp, duration });
      break;
    case "error":
      toast.error(config.message, { id: fp, duration: duration > 4000 ? duration : 6000 });
      break;
    case "warning":
      toast.warning(config.message, { id: fp, duration });
      break;
    default:
      toast.info(config.message, { id: fp, duration });
      break;
  }

  return {
    id: fp + ":" + Date.now(),
    timestamp: Date.now(),
    message: config.message,
    variant: config.variant,
    actionUrl: config.actionUrl,
  };
}

/**
 * Show a toast for a mutation result (success or error).
 * Returns an ActivityFeedEntry for the activity feed.
 */
export function showMutationToast(
  message: string,
  variant: ToastVariant = "success",
  actionUrl?: string,
): ActivityFeedEntry {
  const duration = variant === "error" ? 6000 : 4000;

  switch (variant) {
    case "success":
      toast.success(message, { duration });
      break;
    case "error":
      toast.error(message, { duration });
      break;
    case "warning":
      toast.warning(message, { duration });
      break;
    default:
      toast.info(message, { duration });
      break;
  }

  return {
    id: `mutation:${Date.now()}:${Math.random().toString(36).slice(2)}`,
    timestamp: Date.now(),
    message,
    variant,
    actionUrl,
  };
}
