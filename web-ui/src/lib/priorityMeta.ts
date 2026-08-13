import type { LucideIcon } from "lucide-react";
import { ChevronsUp, Equal, ChevronsDown } from "lucide-react";
import type { Priority } from "@/lib/types";

/**
 * Per-priority display metadata — icon, human label, and accent color classes,
 * mirroring the shape of `roadmapLevel.ts`'s `RoadmapLevelInfo` so priority
 * reads as a sibling concept to the level "kind" accent. The accent reuses the
 * existing `--status-*` semantic tokens (registered in index.css for both
 * themes) rather than new dedicated tokens: high borrows the "error"/attention
 * hue, medium the "warning" hue, low the muted "neutral" hue — so the whole
 * accent lives here with no stylesheet change, the same way `roadmapLevel.ts`
 * borrows the `--color-chart-*` palette.
 *
 * `order` (NOT present on `roadmapLevel.ts`'s equivalent) is the numeric rank
 * used for client-side priority sorting — high (3) > medium (2) > low (1) — so
 * callers that sort a list by priority descending can compare `order` directly
 * instead of re-encoding the low/medium/high ranking at each call site.
 */
export interface PriorityInfo {
  /** Human-readable label — exactly "High" | "Medium" | "Low". */
  label: string;
  /** Priority icon. */
  Icon: LucideIcon;
  /** Text/icon accent class. */
  textClass: string;
  /** Background accent class (paired with `/15` opacity by convention — see ReadinessBadge). */
  bgClass: string;
  /** Border accent class (paired with `/20` opacity by convention). */
  borderClass: string;
  /** Numeric sort rank: high=3, medium=2, low=1. Higher is more urgent. */
  order: number;
}

const PRIORITY_INFO: Record<Priority, PriorityInfo> = {
  high: {
    label: "High",
    Icon: ChevronsUp,
    textClass: "text-status-error",
    bgClass: "bg-status-error/15",
    borderClass: "border-status-error/20",
    order: 3,
  },
  medium: {
    label: "Medium",
    Icon: Equal,
    textClass: "text-status-warning",
    bgClass: "bg-status-warning/15",
    borderClass: "border-status-warning/20",
    order: 2,
  },
  low: {
    label: "Low",
    Icon: ChevronsDown,
    textClass: "text-status-neutral",
    bgClass: "bg-status-neutral/15",
    borderClass: "border-status-neutral/20",
    order: 1,
  },
};

/**
 * The order the three priority levels are offered in every selector/filter —
 * most urgent first, matching how a reviewer scans a prioritized backlog.
 */
export const PRIORITY_ORDER: Priority[] = ["high", "medium", "low"];

/**
 * Returns the icon/label/accent/order for a given priority, or `undefined` for
 * an unknown/missing value — callers rendering possibly-stale cached data
 * (board cards, graph/timeline nodes) can then fall back gracefully rather than
 * indexing into `undefined`. Mirrors `roadmapLevelMeta`'s accessor shape but is
 * null-tolerant because, unlike a hierarchy level, a priority can arrive absent
 * from an older API pod's payload.
 */
export function priorityMeta(priority: string | null | undefined): PriorityInfo | undefined {
  if (priority == null) return undefined;
  return PRIORITY_INFO[priority as Priority];
}
