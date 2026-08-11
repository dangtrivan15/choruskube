import type { TimelineEpicSummary, TimelineStorySummary } from "@/lib/types";

/**
 * Pure helpers deriving the Roadmap Timeline View's per-item and per-Epic-lane "blocked or
 * stalled" risk (visually flagged with a badge, a tint, and a tooltip). `deriveStoryRisk` reads
 * the backend-computed `readiness`/`stalled` fields straight off one Story. `deriveEpicRisk`
 * aggregates that same signal across an Epic's Stories with a plain OR — an Epic lane shows as
 * blocked/stalled if ANY Story under it is, plus the Epic's own `stalled` flag (an Epic has no
 * `readiness` of its own; only Stories/Tasks participate in the dependency graph). Where both
 * blocked and stalled apply to the same item, blocked is treated as the more severe signal and
 * shown first — see `riskDisplayOrder`.
 */

export interface TimelineRisk {
  blocked: boolean;
  stalled: boolean;
}

export function deriveStoryRisk(story: TimelineStorySummary): TimelineRisk {
  return { blocked: story.readiness === "BLOCKED", stalled: story.stalled };
}

export function deriveEpicRisk(epic: TimelineEpicSummary): TimelineRisk {
  const blocked = epic.stories.some((s) => s.readiness === "BLOCKED");
  const stalled = epic.stalled || epic.stories.some((s) => s.stalled);
  return { blocked, stalled };
}

export type RiskDisplay = "blocked" | "stalled" | "blocked-stalled" | "none";

export function riskDisplayOrder(risk: TimelineRisk): RiskDisplay {
  if (risk.blocked && risk.stalled) return "blocked-stalled";
  if (risk.blocked) return "blocked";
  if (risk.stalled) return "stalled";
  return "none";
}
