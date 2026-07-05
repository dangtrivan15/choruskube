import { useState } from "react";
import { useParams } from "react-router";
import { X, ChevronLeft, ChevronRight } from "lucide-react";
import { useRun } from "@/hooks/useRuns";
import { useRunSubscription } from "@/hooks/useRunSubscription";
import { useResizable } from "@/hooks/useResizable";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import RunHeader from "@/components/runs/RunHeader";
import RunMetaBar from "@/components/runs/RunMetaBar";
import PullRequestLinks from "@/components/runs/PullRequestLinks";
import RunDag from "@/components/runs/RunDag";
import DetailPanel from "@/components/runs/DetailPanel";
import RunMetaPanel from "@/components/runs/RunMetaPanel";
import ResizeHandle from "@/components/ui/ResizeHandle";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

export default function RunMonitorPage() {
  const { id } = useParams<{ id: string }>();
  const { data: run, isLoading } = useRun(id!);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [sidebarVisible, setSidebarVisible] = useState(true);
  const isMobile = useMobileBreakpoint();
  useRunSubscription(id);

  const detailPanel = useResizable({
    side: "left",
    defaultWidth: 320,
    minWidth: 240,
    maxWidth: 600,
    storageKey: "detail-panel-width",
  });

  const handleNodeSelect = (nodeId: string | null) => {
    setSelectedNodeId(nodeId);
    if (nodeId !== null) setSidebarVisible(true);
  };

  if (isLoading) {
    return (
      <div className="flex flex-col gap-4 p-4">
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-[500px] w-full" />
      </div>
    );
  }
  if (!run) return <div data-testid="run-not-found" className="p-4 text-muted-foreground">Run not found</div>;

  return (
    <div className={`flex h-full flex-col${detailPanel.isDragging ? " select-none" : ""}`}>
      <RunHeader run={run} />
      {isMobile && <RunMetaBar run={run} />}
      {isMobile && (run.pullRequests?.length ?? 0) > 0 && (
        <PullRequestLinks pullRequests={run.pullRequests} />
      )}
      <div className="relative flex flex-1 overflow-hidden">
        <div className="flex-1">
          <RunDag run={run} onNodeSelect={handleNodeSelect} />
        </div>
        {!isMobile && (
          <>
            {sidebarVisible ? (
              <>
                <ResizeHandle
                  side="left"
                  isDragging={detailPanel.isDragging}
                  onPointerDown={detailPanel.handlePointerDown}
                />
                <div
                  style={{ width: detailPanel.width }}
                  className="shrink-0 border-l overflow-y-auto overflow-x-hidden flex flex-col"
                >
                  {/* Sidebar header with collapse toggle */}
                  <div className="flex justify-end px-2 py-1 border-b shrink-0">
                    <button
                      onClick={() => setSidebarVisible(false)}
                      data-testid="sidebar-collapse-button"
                      className="p-1 text-muted-foreground hover:text-foreground transition-colors"
                      aria-label="Collapse sidebar"
                    >
                      <ChevronRight className="h-4 w-4" />
                    </button>
                  </div>
                  {/* Panel content */}
                  <div className="flex-1 overflow-y-auto overflow-x-hidden">
                    {selectedNodeId ? (
                      <DetailPanel
                        run={run}
                        nodeId={selectedNodeId}
                        onBackToRunMeta={() => setSelectedNodeId(null)}
                      />
                    ) : (
                      <RunMetaPanel run={run} />
                    )}
                  </div>
                </div>
              </>
            ) : (
              /* Thin expand strip when sidebar is collapsed */
              <div className="shrink-0 border-l flex flex-col items-center pt-2">
                <button
                  onClick={() => setSidebarVisible(true)}
                  data-testid="sidebar-expand-button"
                  className="p-1 text-muted-foreground hover:text-foreground transition-colors"
                  aria-label="Expand sidebar"
                >
                  <ChevronLeft className="h-4 w-4" />
                </button>
              </div>
            )}
          </>
        )}
        {/* Transparent overlay prevents React Flow from stealing pointer events during drag */}
        {detailPanel.isDragging && (
          <div className="absolute inset-0 z-20" />
        )}
      </div>

      {/* Mobile detail panel overlay */}
      {selectedNodeId && isMobile && (
        <div
          data-testid="mobile-detail-overlay"
          className="fixed inset-x-0 bottom-0 z-40 flex h-[85vh] flex-col rounded-t-xl border-t bg-background shadow-lg"
        >
          <div className="flex items-center justify-end border-b px-4 py-2">
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => setSelectedNodeId(null)}
              aria-label="Close detail panel"
            >
              <X className="h-4 w-4" />
            </Button>
          </div>
          <div className="flex-1 overflow-y-auto overflow-x-hidden">
            <DetailPanel run={run} nodeId={selectedNodeId} />
          </div>
        </div>
      )}
    </div>
  );
}
