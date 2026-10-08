import { useEffect, useMemo, useRef, useState } from "react";
import { useParams, useSearchParams } from "react-router";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { useRun } from "@/hooks/useRuns";
import { useRunSubscription } from "@/hooks/useRunSubscription";
import { useResizable } from "@/hooks/useResizable";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import { useMediaQuery, DOCKED_PANEL_QUERY } from "@/hooks/useMediaQuery";
import { useFullBleedMain } from "@/components/layout/MainLayoutContext";
import RunHeader from "@/components/runs/RunHeader";
import { RunSummaryStrip, RunSummaryMobileBar, RunSummaryDetails } from "@/components/runs/RunSummary";
import type { AttentionNodeRef } from "@/components/runs/RunSummary";
import RunDag from "@/components/runs/RunDag";
import DetailPanel from "@/components/runs/DetailPanel";
import NodeDetailEmptyState from "@/components/runs/NodeDetailEmptyState";
import BottomSheet from "@/components/ui/BottomSheet";
import ResizeHandle from "@/components/ui/ResizeHandle";
import { Skeleton } from "@/components/ui/skeleton";
import { classifyActiveNodes, pickFocusNode, attentionKind } from "@/lib/runFocus";
import { formatNodeLabel } from "@/components/runs/DagNode";
import type { RunResponse } from "@/lib/types";

/** The `?node=` value, valid only when it names a node in the run's current snapshot. */
function resolveSelectedNodeId(run: RunResponse | undefined, raw: string | null): string | null {
  if (!raw || !run?.graphSnapshot) return null;
  return run.graphSnapshot.nodes.some((n) => n.template_node_id === raw) ? raw : null;
}

function buildAttentionNode(run: RunResponse): AttentionNodeRef | null {
  const node = classifyActiveNodes(run).attention[0];
  if (!node) return null;
  const kind = attentionKind(run, node.template_node_id);
  if (!kind) return null;
  return { templateNodeId: node.template_node_id, label: node.label, kind };
}

export default function RunMonitorPage() {
  const { id } = useParams<{ id: string }>();
  const { data: run, isLoading } = useRun(id!);
  useRunSubscription(id);
  useFullBleedMain();

  const isMobile = useMobileBreakpoint();
  const isDocked = useMediaQuery(DOCKED_PANEL_QUERY);

  const [searchParams, setSearchParams] = useSearchParams();
  const selectedNodeId = resolveSelectedNodeId(run, searchParams.get("node"));

  const [sidebarVisible, setSidebarVisible] = useState(true);
  const [infoOpen, setInfoOpen] = useState(false);

  const detailPanel = useResizable({
    side: "left",
    defaultWidth: 320,
    minWidth: 240,
    maxWidth: 600,
    storageKey: "detail-panel-width",
  });

  function select(nodeId: string | null) {
    const next = new URLSearchParams(searchParams);
    if (nodeId) next.set("node", nodeId);
    else next.delete("node");
    setSearchParams(next, { replace: true });
    if (nodeId !== null) setSidebarVisible(true);
  }

  const focusNodeId = useMemo(() => (run ? pickFocusNode(run) : null), [run]);
  const attentionNode = useMemo(() => (run ? buildAttentionNode(run) : null), [run]);

  // Applies at most once per run id, on the docked tier only: a reload or a live update never
  // re-triggers it, and deselecting afterward doesn't bring it back without reloading.
  const autoFocusedRunIdRef = useRef<string | null>(null);
  useEffect(() => {
    if (!run) return;
    if (autoFocusedRunIdRef.current === run.id) return;
    autoFocusedRunIdRef.current = run.id;
    if (isDocked && selectedNodeId === null && focusNodeId) select(focusNodeId);
    // `select` is re-created every render (it closes over `searchParams`); the ref guard, not
    // the dependency list, is what keeps this to once per run id.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [run?.id, isDocked, selectedNodeId, focusNodeId]);

  if (isLoading) {
    return (
      <div className="flex flex-col gap-4 p-4">
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-[500px] w-full" />
      </div>
    );
  }
  if (!run) return <div data-testid="run-not-found" className="p-4 text-muted-foreground">Run not found</div>;

  const selectedNodeSnapshot = selectedNodeId
    ? run.graphSnapshot?.nodes.find((n) => n.template_node_id === selectedNodeId)
    : undefined;

  return (
    <div className={`flex h-full flex-col${detailPanel.isDragging ? " select-none" : ""}`}>
      <RunHeader run={run} compact={isMobile} />
      {isMobile ? (
        <RunSummaryMobileBar
          run={run}
          attentionNode={attentionNode}
          onOpenInfo={() => setInfoOpen(true)}
          onSelectNode={select}
        />
      ) : (
        <RunSummaryStrip run={run} attentionNode={isDocked ? undefined : attentionNode} onSelectNode={select} />
      )}

      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        <div className="min-w-0 flex-1">
          <RunDag
            run={run}
            onNodeSelect={select}
            selectedNodeId={selectedNodeId}
            focusNodeId={focusNodeId}
            compact={isMobile}
            viewportKey={run.id}
          />
        </div>

        {isDocked && (
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
                  <div className="flex-1 overflow-y-auto overflow-x-hidden">
                    {selectedNodeId ? (
                      <DetailPanel run={run} nodeId={selectedNodeId} onClose={() => select(null)} />
                    ) : (
                      <NodeDetailEmptyState run={run} onSelectNode={select} />
                    )}
                  </div>
                </div>
              </>
            ) : (
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

        {detailPanel.isDragging && <div className="absolute inset-0 z-20" />}
      </div>

      {!isDocked && (
        <BottomSheet
          open={!!selectedNodeId}
          onOpenChange={(open) => {
            if (!open) select(null);
          }}
          title={selectedNodeSnapshot ? formatNodeLabel(selectedNodeSnapshot.label) : "Node details"}
          hideTitle
          data-testid="mobile-detail-overlay"
        >
          {selectedNodeId && <DetailPanel run={run} nodeId={selectedNodeId} />}
        </BottomSheet>
      )}

      {isMobile && (
        <BottomSheet
          open={infoOpen}
          onOpenChange={setInfoOpen}
          title="Run info"
          data-testid="run-info-sheet"
        >
          <RunSummaryDetails run={run} />
        </BottomSheet>
      )}
    </div>
  );
}
