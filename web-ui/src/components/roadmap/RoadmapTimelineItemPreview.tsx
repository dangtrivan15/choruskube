import { stageColors } from "@/lib/timelineStage";
import type { TimelineEpicLaneNodeData, TimelineStoryNodeData } from "@/lib/timelineLayout";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import StalledBadge from "@/components/roadmap/StalledBadge";

interface Props {
  item: TimelineEpicLaneNodeData | TimelineStoryNodeData;
}

/** A Story node's data carries `storyId`; an Epic lane node's does not — the cheapest discriminant
 * between the two without a separate `kind` field on either. */
function isStory(item: TimelineEpicLaneNodeData | TimelineStoryNodeData): item is TimelineStoryNodeData {
  return "storyId" in item;
}

/**
 * Client-only hover preview (item-detail hover/click feature) — rendered as `Tooltip`
 * content on a timeline node. Builds entirely from data already attached to the node
 * (title, stage, parent Epic, blocked/stalled) with no hook and no network request: blockers are
 * the one field that needs a fetch, and they're deliberately left off the hover preview, appearing
 * only in the click-opened `RoadmapTimelineDetailPanel`.
 *
 * An Epic lane's `blocked` is an OR-aggregate across its Stories (see `deriveEpicRisk`), not the
 * Epic's own readiness — Epics don't participate in the dependency graph (`TimelineEpicSummary`
 * has no `readiness` field), so unlike a Story, the Epic preview omits the `ReadinessBadge`
 * entirely rather than showing a misleading "this Epic is blocked" pill.
 */
export default function RoadmapTimelineItemPreview({ item }: Props) {
  const colors = stageColors(item.stage);
  const story = isStory(item) ? item : null;

  return (
    <div data-testid="roadmap-timeline-item-preview" className="flex flex-col gap-1">
      <span className="font-medium">{item.title}</span>
      <span className={colors.text}>{item.stage.replace("_", " ")}</span>
      {story && (
        <span data-testid="roadmap-timeline-item-preview-parent" className="text-background/70">
          in {story.epicTitle}
        </span>
      )}
      <div className="flex items-center gap-1">
        {story && <ReadinessBadge readiness={story.blocked ? "BLOCKED" : null} size="compact" />}
        <StalledBadge stalled={item.stalled} size="compact" />
      </div>
    </div>
  );
}
