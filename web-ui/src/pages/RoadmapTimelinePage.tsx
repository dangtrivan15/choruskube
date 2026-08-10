import { Link } from "react-router";
import { ArrowLeft } from "lucide-react";
import { useRoadmapTimeline } from "@/hooks/useRoadmapTimeline";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import RoadmapTimeline from "@/components/roadmap/RoadmapTimeline";
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
  useRoadmapSubscription();

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
        <Link
          to="/roadmap"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          Back to Roadmap
        </Link>
      </PageHeader>

      <div className="relative flex-1 overflow-hidden">
        {!data || data.epics.length === 0 ? (
          // Dedicated empty-state message (mirrors RoadmapPage's `epic-list-empty` banner) rather
          // than a silent zero-lane canvas — an empty ReactFlow canvas gives no visual
          // confirmation the fetch succeeded vs. is still in flight or failed.
          <div data-testid="roadmap-timeline-empty" className="p-6 text-center text-muted-foreground text-sm">
            No epics yet. Create one from the Roadmap list to see it on the timeline.
          </div>
        ) : (
          <RoadmapTimeline data={data} />
        )}
      </div>
    </div>
  );
}
