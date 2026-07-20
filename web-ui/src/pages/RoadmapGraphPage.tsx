import { useMemo, useState } from "react";
import { Link, useParams } from "react-router";
import { ArrowLeft, X } from "lucide-react";
import { useRoadmapGraph } from "@/hooks/useRoadmapGraph";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import RoadmapGraph from "@/components/roadmap/RoadmapGraph";
import RoadmapGraphDetailPanel, {
  type RoadmapDetailItem,
  type BlockableItemRef,
} from "@/components/roadmap/RoadmapGraphDetailPanel";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import PageHeader from "@/components/layout/PageHeader";
import type { RoadmapGraphSnapshot } from "@/lib/types";

function buildBlockableItems(snapshot: RoadmapGraphSnapshot): BlockableItemRef[] {
  return [
    ...snapshot.stories.map((s): BlockableItemRef => ({ id: s.id, itemType: "story", title: s.title })),
    ...snapshot.tasks.map((t): BlockableItemRef => ({ id: t.id, itemType: "task", title: t.title })),
  ];
}

/**
 * Roadmap Graph View for a single Epic — the Epic's Story/Task tree plus
 * blocking dependency edges, rendered with RoadmapGraph and a click-to-open
 * detail sidebar. Mirrors RunMonitorPage's fetch + STOMP-subscribe + graph +
 * detail-panel composition.
 */
export default function RoadmapGraphPage() {
  const { epicId } = useParams<{ epicId: string }>();
  const { data: snapshot, isLoading } = useRoadmapGraph(epicId);
  const [selected, setSelected] = useState<RoadmapDetailItem | null>(null);
  const isMobile = useMobileBreakpoint();
  useRoadmapSubscription();

  const blockableItems = useMemo(() => (snapshot ? buildBlockableItems(snapshot) : []), [snapshot]);

  if (isLoading) {
    return (
      <div className="flex flex-col gap-4 p-4">
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-[500px] w-full" />
      </div>
    );
  }

  if (!snapshot) {
    return (
      <div data-testid="roadmap-graph-not-found" className="p-4 text-muted-foreground">
        Epic not found
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <PageHeader title={`${snapshot.epic.title} — Graph`} data-testid="roadmap-graph-heading">
        <Link
          to={`/roadmap/epics/${snapshot.epic.id}`}
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          Back to Epic
        </Link>
      </PageHeader>

      <div className="relative flex flex-1 overflow-hidden">
        <div className="flex-1">
          <RoadmapGraph snapshot={snapshot} onNodeSelect={setSelected} />
        </div>

        {!isMobile && selected && (
          <div className="w-[360px] shrink-0 overflow-y-auto overflow-x-hidden border-l">
            <div className="flex justify-end px-2 py-1 border-b">
              <button
                onClick={() => setSelected(null)}
                data-testid="roadmap-graph-detail-close"
                className="p-1 text-muted-foreground hover:text-foreground transition-colors"
                aria-label="Close detail panel"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <RoadmapGraphDetailPanel
              detail={selected}
              epicId={snapshot.epic.id}
              dependencies={snapshot.dependencies}
              blockableItems={blockableItems}
              externalBlockers={snapshot.externalBlockers}
            />
          </div>
        )}
      </div>

      {selected && isMobile && (
        <div
          data-testid="roadmap-graph-mobile-detail-overlay"
          className="fixed inset-x-0 bottom-0 z-40 flex h-[85vh] flex-col rounded-t-xl border-t bg-background shadow-lg"
        >
          <div className="flex items-center justify-end border-b px-4 py-2">
            <Button variant="ghost" size="icon-sm" onClick={() => setSelected(null)} aria-label="Close detail panel">
              <X className="h-4 w-4" />
            </Button>
          </div>
          <div className="flex-1 overflow-y-auto overflow-x-hidden">
            <RoadmapGraphDetailPanel
              detail={selected}
              epicId={snapshot.epic.id}
              dependencies={snapshot.dependencies}
              blockableItems={blockableItems}
              externalBlockers={snapshot.externalBlockers}
            />
          </div>
        </div>
      )}
    </div>
  );
}
