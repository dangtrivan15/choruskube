import { Link } from "react-router";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { stageColors } from "@/lib/timelineStage";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import StalledBadge from "@/components/roadmap/StalledBadge";
import BlockingChainSection from "@/components/roadmap/BlockingChainSection";
import { useBlockingChain } from "@/hooks/useBlockingChain";
import type { TimelineEpicSummary, TimelineStorySummary } from "@/lib/types";

interface Props {
  epic: TimelineEpicSummary;
  /** Present only when the focused item is a Story nested under `epic`; absent when the focused
   * item is the Epic lane itself (the panel is keyed off the same URL focus that
   * drives everything else, and focus can point at either granularity). */
  story?: TimelineStorySummary;
  onClose(): void;
}

/**
 * Story rollup shown when only an Epic (no Story) is focused — counts of its Stories that are
 * blocked/stalled, since the Epic itself has no `readiness` of its own (Epics don't participate
 * in the dependency graph) and showing every Story's detail here would duplicate the Graph view.
 */
function EpicStoryRollup({ epic }: { epic: TimelineEpicSummary }) {
  const blockedCount = epic.stories.filter((s) => s.readiness === "BLOCKED").length;
  const stalledCount = epic.stories.filter((s) => s.stalled).length;

  return (
    <div data-testid="roadmap-timeline-detail-rollup" className="pt-3 border-t space-y-1 text-sm text-muted-foreground">
      <p>
        {epic.stories.length} {epic.stories.length === 1 ? "Story" : "Stories"}
      </p>
      <p>
        {blockedCount} blocked, {stalledCount} stalled
      </p>
    </div>
  );
}

/**
 * Roadmap Timeline View's read-only detail panel — opened when a Story marker or Epic lane is
 * clicked (driven by the same URL focus the pan-to-center behavior already uses).
 * Deliberately a new, small component rather than reusing `RoadmapGraphDetailPanel`:
 * the Timeline's data model is Epic/Story-only (never Task), read-oriented (no dependency editing),
 * and every field but the blockers is already on screen — only the blocking chain, for a BLOCKED
 * Story, needs a fetch.
 */
export default function RoadmapTimelineDetailPanel({ epic, story, onClose }: Props) {
  const item = story ?? epic;
  const colors = stageColors(item.stage);

  // Hooks must run unconditionally on every render — `enabled` is what actually gates the
  // network call, so this is harmlessly inert (query stays disabled) when nothing is focused as a
  // Story at all.
  const chainQuery = useBlockingChain("story", story?.id ?? "", story?.readiness === "BLOCKED");

  return (
    <div data-testid="roadmap-timeline-detail-panel" className="p-4 space-y-4">
      <div className="flex items-start justify-between gap-2">
        <div className="space-y-2">
          <h2 data-testid="roadmap-timeline-detail-title" className="text-lg font-semibold break-words">
            {item.title}
          </h2>
          <div data-testid="roadmap-timeline-detail-stage" className="flex flex-wrap items-center gap-2">
            <span className={`text-xs font-medium uppercase tracking-wide ${colors.text}`}>
              {item.stage.replace("_", " ")}
            </span>
            {story && <ReadinessBadge readiness={story.readiness} />}
            <StalledBadge stalled={item.stalled} />
          </div>
        </div>
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={onClose}
          data-testid="roadmap-timeline-detail-close"
          aria-label="Close detail panel"
        >
          <X className="size-4" />
        </Button>
      </div>

      {story && (
        <div data-testid="roadmap-timeline-detail-parent" className="text-sm">
          <span className="text-muted-foreground">Epic: </span>
          <Link to={`/roadmap/epics/${epic.id}/graph`} className="text-primary hover:underline">
            {epic.title}
          </Link>
        </div>
      )}

      {story ? (
        story.readiness === "BLOCKED" && <BlockingChainSection chain={chainQuery.data} isLoading={chainQuery.isLoading} />
      ) : (
        <EpicStoryRollup epic={epic} />
      )}
    </div>
  );
}
