import { statusColorTokens } from "@/lib/statusColors";

/**
 * Roadmap board stages (backlog/in_progress/rolled_out) don't share a vocabulary with the
 * semantic status tokens (success/warning/...), so map them the same way RoadmapGraphNode maps
 * work-item status into a color token.
 *
 * Extracted out of `RoadmapTimelineNode` (where it was module-private) so the timeline node, the
 * hover preview, and the detail panel all render a stage badge identically instead of each
 * re-deriving the mapping over `statusColorTokens` (item-detail hover/click feature).
 */
export const STAGE_TOKEN_MAP: Record<string, string> = {
  backlog: "pending",
  in_progress: "running",
  rolled_out: "completed",
};

export function stageColors(stage: string) {
  const tokens = statusColorTokens(STAGE_TOKEN_MAP[stage] ?? stage);
  return { bg: `${tokens.bg}/10`, border: `${tokens.border}/60`, text: tokens.text };
}
