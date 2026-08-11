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
 * Per-level icon, "kind" label, and accent color classes (Decisions 2 and 3
 * of the spec). Drawn from the existing `--color-chart-*` palette tokens
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
    Icon: Milestone,
    textClass: "text-chart-2",
    bgClass: "bg-chart-2/10",
    borderClass: "border-chart-2/30",
  },
  story: {
    label: "Story",
    Icon: BookOpen,
    textClass: "text-chart-3",
    bgClass: "bg-chart-3/10",
    borderClass: "border-chart-3/30",
  },
  task: {
    label: "Task",
    Icon: ListTodo,
    textClass: "text-chart-1",
    bgClass: "bg-chart-1/10",
    borderClass: "border-chart-1/30",
  },
};

/** Returns the icon/label/accent for a given roadmap hierarchy level. */
export function roadmapLevelMeta(level: RoadmapLevel): RoadmapLevelInfo {
  return LEVEL_INFO[level];
}
