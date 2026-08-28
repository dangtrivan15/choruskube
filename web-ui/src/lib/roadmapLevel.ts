import type { LucideIcon } from "lucide-react";
import { Milestone, BookOpen, ListTodo } from "lucide-react";
import type { RoadmapItemType } from "@/components/roadmap/RoadmapGraphNode";

/**
 * Which hierarchy level (Epic/Story/Task) a roadmap item is at. Re-exports
 * `RoadmapGraphNode`'s `RoadmapItemType` rather than redefining an
 * equivalent union, so the graph and every other consumer of level identity
 * (detail pages, board cards, this helper) share one source of truth for the
 * three valid values.
 */
export type RoadmapLevel = RoadmapItemType;

export interface RoadmapLevelInfo {
  /** Human-readable "kind" label — exactly "Epic" | "Story" | "Task". */
  label: string;
  /**
   * The same label naming a *set* of items rather than one — "Epics" | "Stories" | "Tasks".
   * Carried here rather than derived by appending an "s" at the call site, because "Story"
   * doesn't pluralize that way.
   */
  pluralLabel: string;
  /** Level icon, reusing the vocabulary the roadmap graph already established. */
  Icon: LucideIcon;
  /** Text/icon accent class. */
  textClass: string;
  /** Background accent class (paired with `/10` opacity by convention — see statusColorTokens). */
  bgClass: string;
  /** Border accent class (paired with `/30` opacity by convention). */
  borderClass: string;
}

/**
 * Per-level icon, "kind" label, and accent color classes. Drawn from the
 * existing `--color-chart-*` palette tokens
 * (registered in index.css for both themes) rather than new dedicated
 * tokens, so the accent lives entirely here with no stylesheet change. Three
 * distinct chart hues keep Epic/Story/Task mutually distinguishable; the
 * accent is always rendered as a spatially separate icon + label (never as a
 * status badge), so it stays visually orthogonal to the `--status-*`
 * work-item-status tokens even where hues partially overlap.
 */
const LEVEL_INFO: Record<RoadmapLevel, RoadmapLevelInfo> = {
  epic: {
    label: "Epic",
    pluralLabel: "Epics",
    Icon: Milestone,
    textClass: "text-chart-2",
    bgClass: "bg-chart-2/10",
    borderClass: "border-chart-2/30",
  },
  story: {
    label: "Story",
    pluralLabel: "Stories",
    Icon: BookOpen,
    textClass: "text-chart-3",
    bgClass: "bg-chart-3/10",
    borderClass: "border-chart-3/30",
  },
  task: {
    label: "Task",
    pluralLabel: "Tasks",
    Icon: ListTodo,
    textClass: "text-chart-1",
    bgClass: "bg-chart-1/10",
    borderClass: "border-chart-1/30",
  },
};

/**
 * The order the three hierarchy levels are offered in every selector, top of
 * the hierarchy first. Exported next to `roadmapLevelMeta` for the same reason
 * `PRIORITY_ORDER` sits next to `priorityMeta`: `LEVEL_INFO` is module-private,
 * so a selector that wants to render one control per level would otherwise have
 * to re-list the three values itself.
 */
export const ROADMAP_LEVEL_ORDER: RoadmapLevel[] = ["epic", "story", "task"];

/** Returns the icon/label/accent for a given roadmap hierarchy level. */
export function roadmapLevelMeta(level: RoadmapLevel): RoadmapLevelInfo {
  return LEVEL_INFO[level];
}
