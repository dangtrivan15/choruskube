import { memo } from "react";
import type { NodeProps } from "@xyflow/react";
import { Milestone, BookOpen } from "lucide-react";
import { cn } from "@/lib/utils";
import { statusColorTokens } from "@/lib/statusColors";
import type { TimelineEpicLaneNodeType, TimelineStoryNodeType } from "@/lib/timelineLayout";

/**
 * Roadmap board stages (backlog/in_progress/rolled_out) don't share a vocabulary with the
 * semantic status tokens (success/warning/...), so map them the same way RoadmapGraphNode maps
 * work-item status into a color token.
 */
const STAGE_TOKEN_MAP: Record<string, string> = {
  backlog: "pending",
  in_progress: "running",
  rolled_out: "completed",
};

function stageColors(stage: string) {
  const tokens = statusColorTokens(STAGE_TOKEN_MAP[stage] ?? stage);
  return { bg: `${tokens.bg}/10`, border: `${tokens.border}/60`, text: tokens.text };
}

/**
 * Lane header node — one per Epic, pinned at the left of its row (x=0, see
 * computeRoadmapTimelineLayout). Purely a label; the Epic's Stories on the same lane carry the
 * actual time-plotted markers.
 */
function EpicLaneNode({ data }: NodeProps<TimelineEpicLaneNodeType>) {
  const colors = stageColors(data.stage);
  return (
    <div
      data-testid="roadmap-timeline-epic-lane"
      data-label={data.title}
      className={cn(
        "flex w-[200px] items-center gap-2 rounded-lg border-2 px-3 py-2 shadow-sm",
        colors.bg,
        colors.border,
      )}
    >
      <span className={cn("shrink-0", colors.text)}>
        <Milestone className="size-4" />
      </span>
      <span className="truncate text-sm font-medium">{data.title}</span>
    </div>
  );
}

/**
 * Story marker node — plotted along its Epic's lane at an X position derived from the Story's
 * `createdAt` (see computeRoadmapTimelineLayout's time scale). A small dot-plus-label rather than
 * the full card RoadmapGraphNode renders, since a lane can hold many markers side by side.
 */
function StoryNode({ data }: NodeProps<TimelineStoryNodeType>) {
  const colors = stageColors(data.stage);
  return (
    <div
      data-testid="roadmap-timeline-story-node"
      data-label={data.title}
      title={data.title}
      className={cn(
        "flex w-[140px] items-center gap-1.5 rounded-md border px-1.5 py-1 text-xs shadow-sm",
        colors.bg,
        colors.border,
      )}
    >
      <span className={cn("shrink-0", colors.text)}>
        <BookOpen className="size-3" />
      </span>
      <span className="truncate">{data.title}</span>
    </div>
  );
}

export const RoadmapTimelineEpicLaneNode = memo(EpicLaneNode);
export const RoadmapTimelineStoryNode = memo(StoryNode);
