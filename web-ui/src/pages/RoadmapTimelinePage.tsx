import { Link, useSearchParams } from "react-router";
import { ArrowLeft } from "lucide-react";
import { useRoadmapTimeline } from "@/hooks/useRoadmapTimeline";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import { parseFocusParams, focusToSearchParamsInit } from "@/lib/roadmapFocus";
import RoadmapTimeline from "@/components/roadmap/RoadmapTimeline";
import RoadmapTimelineDetailPanel from "@/components/roadmap/RoadmapTimelineDetailPanel";
import RoadmapViewControls from "@/components/roadmap/RoadmapViewControls";
import { Skeleton } from "@/components/ui/skeleton";
import PageHeader from "@/components/layout/PageHeader";

/**
 * Roadmap Timeline View — Epics as horizontal swimlanes with their Stories plotted along a
 * shared time axis. Mirrors RoadmapGraphPage's fetch + STOMP-subscribe + canvas composition, but
 * fetches the aggregate `GET /api/v1/roadmap/timeline` endpoint (the whole org roadmap in one
 * request) rather than a single Epic's graph.
 */
export default function RoadmapTimelinePage() {
  const { data, isLoading } = useRoadmapTimeline();
  const [searchParams, setSearchParams] = useSearchParams();
  const isMobile = useMobileBreakpoint();
  useRoadmapSubscription();

  const rawFocus = parseFocusParams(searchParams);
  // A URL-carried id that doesn't resolve against the freshly-fetched data (deleted, garbage, or
  // simply an id from a different org —'s Negative/security case) is treated as "nothing
  // focused" rather than partially rendered or thrown on, all the way out to the switcher: an
  // unresolved epic must not leave Graph looking enabled for an Epic that isn't really there.
  const focusedEpic = data?.epics.find((e) => e.id === rawFocus.epicId);
  const focusedEpicId = focusedEpic?.id;
  const focusedStory = focusedEpic?.stories.find((s) => s.id === rawFocus.storyId);
  const focusedStoryId = focusedStory?.id;

  function handleFocusChange(epicId: string, storyId?: string) {
    // history replace, not push  — ordinary lane/marker browsing shouldn't balloon the
    // back-button history the way a `push` on every click would.
    setSearchParams(focusToSearchParamsInit({ epicId, storyId }), { replace: true });
  }

  // Closing the panel clears focus entirely — the panel's open/close state is
  // literally driven by whether something is focused, so there is no separate "close" concept.
  function handleClosePanel() {
    setSearchParams({}, { replace: true });
  }

  if (isLoading) {
    return (
      <div className="flex flex-col gap-4 p-4">
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-[500px] w-full" />
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <PageHeader title="Roadmap — Timeline" data-testid="roadmap-timeline-heading">
        <RoadmapViewControls
          level="epic"
          view="timeline"
          focusedEpicId={focusedEpicId}
          focusedStoryId={focusedStoryId}
        />
        <Link
          to="/roadmap"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          Back to Roadmap
        </Link>
      </PageHeader>

      <div className="relative flex flex-1 overflow-hidden">
        <div className="relative flex-1 overflow-hidden">
          {!data || data.epics.length === 0 ? (
            // Dedicated empty-state message (mirrors RoadmapPage's `epic-list-empty` banner) rather
            // than a silent zero-lane canvas — an empty ReactFlow canvas gives no visual
            // confirmation the fetch succeeded vs. is still in flight or failed.
            <div data-testid="roadmap-timeline-empty" className="p-6 text-center text-muted-foreground text-sm">
              No epics yet. Create one from the Roadmap list to see it on the timeline.
            </div>
          ) : (
            <RoadmapTimeline
              data={data}
              focusedEpicId={focusedEpicId}
              focusedStoryId={focusedStoryId}
              onFocusChange={handleFocusChange}
            />
          )}
        </div>

        {/* Desktop: inline side panel, mirroring RoadmapGraphPage (item-detail hover/click —
            the panel's visibility is driven purely by whether an Epic is focused). */}
        {!isMobile && focusedEpic && (
          <div className="w-[360px] shrink-0 overflow-y-auto overflow-x-hidden border-l">
            <RoadmapTimelineDetailPanel epic={focusedEpic} story={focusedStory} onClose={handleClosePanel} />
          </div>
        )}
      </div>

      {/* Mobile: bottom-sheet overlay, mirroring RoadmapGraphPage's mobile detail overlay. */}
      {isMobile && focusedEpic && (
        <div
          data-testid="roadmap-timeline-mobile-detail-overlay"
          className="fixed inset-x-0 bottom-0 z-40 flex h-[85vh] flex-col rounded-t-xl border-t bg-background shadow-lg"
        >
          <div className="flex-1 overflow-y-auto overflow-x-hidden">
            <RoadmapTimelineDetailPanel epic={focusedEpic} story={focusedStory} onClose={handleClosePanel} />
          </div>
        </div>
      )}
    </div>
  );
}
