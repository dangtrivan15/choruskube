import { memo, type KeyboardEvent } from "react";
import type { NodeProps } from "@xyflow/react";
import { Milestone, BookOpen } from "lucide-react";
import { cn } from "@/lib/utils";
import { stageColors } from "@/lib/timelineStage";
import { useTimelineFocusActivate } from "@/lib/timelineFocus";
import type { TimelineEpicLaneNodeType, TimelineStoryNodeType } from "@/lib/timelineLayout";
import { riskDisplayOrder } from "@/lib/timelineRisk";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import StalledBadge from "@/components/roadmap/StalledBadge";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import RoadmapTimelineItemPreview from "@/components/roadmap/RoadmapTimelineItemPreview";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";

/**
 * Highlight styling applied to a lane/marker whose `data.isFocused` is true  — the same
 * ring treatment `EpicBoardCard` uses for its own focused-Epic highlight, so a focused item reads
 * consistently across Board and Timeline.
 */
const FOCUS_RING_CLASS = "ring-2 ring-ring ring-offset-1";

/** Enter/Space activates a node the same way a click does (item-detail hover/click). */
function isActivationKey(event: KeyboardEvent<HTMLDivElement>): boolean {
  return event.key === "Enter" || event.key === " " || event.key === "Spacebar";
}

/**
 * Lane header node — one per Epic, pinned at the left of its row (x=0, see
 * computeRoadmapTimelineLayout). Purely a label; the Epic's Stories on the same lane carry the
 * actual time-plotted markers. Clickable — the actual event handling lives on the parent
 * `<ReactFlow>`'s `onNodeClick` (RoadmapTimeline), this component only needs to look interactive
 * and stay clickable (no `pointer-events: none`). Also keyboard-activatable (Enter/Space) via
 * `useTimelineFocusActivate` — the same wrapper-level focus call a click makes  — and shows
 * a hover preview: a non-interactive `Tooltip` rendering
 * `RoadmapTimelineItemPreview`, matching the `TruncatedText` idiom already used elsewhere.
 */
function EpicLaneNode({ data }: NodeProps<TimelineEpicLaneNodeType>) {
  const colors = stageColors(data.stage);
  const risk = riskDisplayOrder(data);
  const activate = useTimelineFocusActivate();
  const title = [
    data.title,
    data.blocked && "Contains a blocked item",
    data.stalled && "Contains a stalled item",
  ]
    .filter(Boolean)
    .join(" — ");

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!isActivationKey(event)) return;
    event.preventDefault();
    activate?.(data.epicId);
  }

  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <div
            data-testid="roadmap-timeline-epic-lane"
            data-label={data.title}
            data-focused={data.isFocused ? "true" : "false"}
            data-risk={risk}
            title={title}
            role="button"
            tabIndex={0}
            onKeyDown={handleKeyDown}
            className={cn(
              "flex w-[200px] cursor-pointer items-center gap-2 rounded-lg border-2 px-3 py-2 shadow-sm transition-shadow",
              colors.bg,
              colors.border,
              data.isFocused && FOCUS_RING_CLASS,
              data.blocked ? "ring-1 ring-status-warning/40" : data.stalled ? "ring-1 ring-status-neutral/40" : "",
            )}
          />
        }
      >
        <span className={cn("shrink-0", colors.text)}>
          <Milestone className="size-4" />
        </span>
        <span className="truncate text-sm font-medium">{data.title}</span>
        <PriorityBadge priority={data.priority} size="compact" data-testid="roadmap-timeline-epic-priority-badge" />
        <ReadinessBadge
          readiness={data.blocked ? "BLOCKED" : null}
          size="compact"
          data-testid="roadmap-timeline-epic-blocked-badge"
        />
        {data.stalled && (
          <StalledBadge stalled size="compact" data-testid="roadmap-timeline-epic-stalled-badge" />
        )}
      </TooltipTrigger>
      <TooltipContent>
        <RoadmapTimelineItemPreview item={data} />
      </TooltipContent>
    </Tooltip>
  );
}

/**
 * Story marker node — plotted along its Epic's lane at an X position derived from the Story's
 * `createdAt` (see computeRoadmapTimelineLayout's time scale). A small dot-plus-label rather than
 * the full card RoadmapGraphNode renders, since a lane can hold many markers side by side.
 * Clickable, keyboard-activatable, and hover-previewed — see EpicLaneNode's doc comment above.
 */
function StoryNode({ data }: NodeProps<TimelineStoryNodeType>) {
  const colors = stageColors(data.stage);
  const risk = riskDisplayOrder(data);
  const activate = useTimelineFocusActivate();
  const title = [
    data.title,
    data.blocked && "Blocked by an unfinished dependency",
    data.stalled && "Stalled — no recent activity",
  ]
    .filter(Boolean)
    .join(" — ");

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!isActivationKey(event)) return;
    event.preventDefault();
    activate?.(data.epicId, data.storyId);
  }

  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <div
            data-testid="roadmap-timeline-story-node"
            data-label={data.title}
            data-focused={data.isFocused ? "true" : "false"}
            data-risk={risk}
            title={title}
            role="button"
            tabIndex={0}
            onKeyDown={handleKeyDown}
            className={cn(
              "flex w-[140px] cursor-pointer items-center gap-1.5 rounded-md border px-1.5 py-1 text-xs shadow-sm transition-shadow",
              colors.bg,
              colors.border,
              data.isFocused && FOCUS_RING_CLASS,
              data.blocked ? "ring-1 ring-status-warning/40" : data.stalled ? "ring-1 ring-status-neutral/40" : "",
            )}
          />
        }
      >
        <span className={cn("shrink-0", colors.text)}>
          <BookOpen className="size-3" />
        </span>
        <span className="truncate">{data.title}</span>
        <PriorityBadge priority={data.priority} size="compact" data-testid="roadmap-timeline-story-priority-badge" />
        <ReadinessBadge
          readiness={data.blocked ? "BLOCKED" : null}
          size="compact"
          data-testid="roadmap-timeline-story-blocked-badge"
        />
        {data.stalled && (
          <StalledBadge stalled size="compact" data-testid="roadmap-timeline-story-stalled-badge" />
        )}
      </TooltipTrigger>
      <TooltipContent>
        <RoadmapTimelineItemPreview item={data} />
      </TooltipContent>
    </Tooltip>
  );
}

export const RoadmapTimelineEpicLaneNode = memo(EpicLaneNode);
export const RoadmapTimelineStoryNode = memo(StoryNode);
