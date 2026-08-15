import type { LucideIcon } from "lucide-react";
import { Flag } from "lucide-react";

export interface MilestoneInfo {
  /** Milestone display icon — shared across every Milestone (there is no per-kind icon, unlike
   * Priority/RoadmapLevel: a Milestone is a free-form user-created label, not a fixed enum). */
  Icon: LucideIcon;
  /** Text/icon accent class. */
  textClass: string;
  /** Background accent class (paired with `/15` opacity by convention — see `PriorityBadge`). */
  bgClass: string;
  /** Border accent class (paired with `/20` opacity by convention). */
  borderClass: string;
}

/**
 * Accent palette a Milestone's chip picks from, deterministically, by its id — reusing the
 * existing `--color-chart-*` tokens (registered in index.css for both themes) rather than new
 * dedicated tokens, mirroring `roadmapLevel.ts`'s and `priorityMeta.ts`'s existing convention.
 * Unlike those two (a fixed 3-value enum with one accent apiece), a Milestone is a free-form
 * user-created label with no bounded set of values — the palette exists purely so distinct
 * Milestones read as visually distinct chips on a crowded Roadmap list/timeline, not to encode
 * meaning in any one color.
 */
const PALETTE: Omit<MilestoneInfo, "Icon">[] = [
  { textClass: "text-chart-1", bgClass: "bg-chart-1/15", borderClass: "border-chart-1/20" },
  { textClass: "text-chart-2", bgClass: "bg-chart-2/15", borderClass: "border-chart-2/20" },
  { textClass: "text-chart-3", bgClass: "bg-chart-3/15", borderClass: "border-chart-3/20" },
  { textClass: "text-chart-4", bgClass: "bg-chart-4/15", borderClass: "border-chart-4/20" },
  { textClass: "text-chart-5", bgClass: "bg-chart-5/15", borderClass: "border-chart-5/20" },
];

/** Small, non-cryptographic string hash (djb2) — deterministic across renders/reloads. */
function hashString(value: string): number {
  let hash = 5381;
  for (let i = 0; i < value.length; i++) {
    hash = (hash * 33) ^ value.charCodeAt(i);
  }
  return Math.abs(hash);
}

/**
 * Returns the icon/accent for a Milestone, keyed off its `id` (stable across a rename, so a
 * given Milestone's chip color never shifts just because its name changed).
 */
export function milestoneMeta(milestoneId: string): MilestoneInfo {
  const palette = PALETTE[hashString(milestoneId) % PALETTE.length];
  return { Icon: Flag, ...palette };
}
