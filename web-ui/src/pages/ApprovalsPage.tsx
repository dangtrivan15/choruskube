import { useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import {
  CheckCircle,
  Clock,
  ChevronDown,
  ChevronRight,
  ExternalLink,
  Maximize2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import DecisionButtons, { LEGACY_DECISION_OPTIONS } from "@/components/runs/DecisionButtons";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import TruncatedText from "@/components/ui/TruncatedText";
import Pagination from "@/components/ui/Pagination";
import SortDropdown from "@/components/ui/SortDropdown";
import EmptyState from "@/components/ui/EmptyState";
import ErrorAlert from "@/components/ui/ErrorAlert";
import { usePendingGates, useSignalFromDashboard } from "@/hooks/usePendingGates";
import { usePermission } from "@/hooks/usePermission";
import { usePendingGatesSubscription } from "@/hooks/usePendingGatesSubscription";
import PredecessorOutputDialog from "@/components/runs/PredecessorOutputDialog";
import ArtifactBrowser from "@/components/runs/ArtifactBrowser";
import ArtifactList from "@/components/runs/ArtifactList";
import LiveChatPanel from "@/components/runs/LiveChatPanel";
import FileUploadZone from "@/components/runs/FileUploadZone";
import RoadmapCandidateBreakdown from "@/components/runs/RoadmapCandidateBreakdown";
import EscalationGatePanel from "@/components/runs/EscalationGatePanel";
import PageHeader from "@/components/layout/PageHeader";
import PageShell from "@/components/layout/PageShell";
import { isEscalationGate } from "@/lib/decisions";
import type { PendingGateResponse, SortParam, PaginationParams, RoadmapCandidatesDocument } from "@/lib/types";

const SORT_OPTIONS = [
  { label: "Waiting (oldest first)", field: "startedAt", direction: "asc" as const },
  { label: "Waiting (newest first)", field: "startedAt", direction: "desc" as const },
];

export default function ApprovalsPage() {
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<SortParam | null>(null);

  const pagination: PaginationParams = { page, size: 20, sort };
  const { data: pageData, isLoading, isError } = usePendingGates(pagination);
  const gates = pageData?.content;
  usePendingGatesSubscription();

  return (
    <PageShell>
      <PageHeader title="Approvals" data-testid="approvals-heading">
        {gates && gates.length > 0 && (
          <Badge data-testid="approvals-pending-badge" variant="outline" className="text-muted-foreground">
            {pageData?.totalElements ?? gates.length} pending
          </Badge>
        )}
        <SortDropdown options={SORT_OPTIONS} currentSort={sort} onSort={setSort} />
      </PageHeader>

      {isLoading && (
        <div className="space-y-4">
          {[1, 2, 3].map((i) => (
            <Skeleton key={i} className="h-48 w-full rounded-lg" />
          ))}
        </div>
      )}

      {isError && (
        <ErrorAlert message="Failed to load pending approvals. Please try again." />
      )}

      {!isLoading && !isError && gates?.length === 0 && (
        <EmptyState
          icon={<CheckCircle className="h-10 w-10" />}
          title="No pending approvals"
          description="All human gates have been resolved."
        />
      )}

      {gates && gates.length > 0 && (
        <div className="space-y-4">
          {gates.map((gate) => (
            <GateCard key={gate.nodeExecutionId} gate={gate} />
          ))}
        </div>
      )}

      {pageData && (
        <Pagination
          page={pageData.number}
          totalPages={pageData.totalPages}
          onPageChange={setPage}
        />
      )}
    </PageShell>
  );
}

function GateCard({ gate }: { gate: PendingGateResponse }) {
  const [feedback, setFeedback] = useState("");
  const [outputExpanded, setOutputExpanded] = useState(false);
  const [expandedPredIdx, setExpandedPredIdx] = useState<number | null>(null);
  const [attachmentFiles, setAttachmentFiles] = useState<File[]>([]);
  const [editedCandidates, setEditedCandidates] = useState<RoadmapCandidatesDocument>(
    gate.candidateBreakdown ?? { milestones: [], epics: [], dependencies: [] }
  );
  const signalMutation = useSignalFromDashboard();
  const isLiveChat = gate.status === "live_chat";
  const { canOperate } = usePermission();
  const options = gate.decisionOptions ?? LEGACY_DECISION_OPTIONS;
  const isEscalation = isEscalationGate(options);

  function handleSubmit(decision: string) {
    // rereview/redraft/route:* expect the typed guidance as `human_guidance.md` so the
    // next node reads it from /workspace/in/<gate_label>/human_guidance.md.
    let files = attachmentFiles;
    if (
      (decision === "rereview" || decision === "redraft" || decision.startsWith("route:")) &&
      feedback.trim()
    ) {
      const guidanceFile = new File([feedback], "human_guidance.md", { type: "text/markdown" });
      files = [guidanceFile, ...attachmentFiles];
    }
    signalMutation.mutate(
      {
        runId: gate.runId,
        nodeExecId: gate.nodeExecutionId,
        decision,
        feedback,
        files,
        ...(gate.candidateBreakdown != null ? { editedCandidates } : {}),
      },
      {
        onSuccess: () => {
          setFeedback("");
          setAttachmentFiles([]);
        },
      }
    );
  }

  const waitingDuration = gate.waitingSince
    ? formatDistanceToNow(new Date(gate.waitingSince), { addSuffix: false })
    : null;

  return (
    <div data-testid="gate-card" className="rounded-lg border bg-card p-4 shadow-sm">
      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <TruncatedText as="h3" className="text-sm font-semibold">{gate.nodeLabel}</TruncatedText>
            <Badge variant="outline" className="shrink-0">
              Iteration {gate.iteration}
            </Badge>
            {isLiveChat && (
              <Badge data-testid="live-chat-badge" className="shrink-0 bg-status-accent/15 text-status-accent">
                Live Chat
              </Badge>
            )}
          </div>
          <div className="mt-1 flex items-center gap-2 text-xs text-muted-foreground">
            <TruncatedText>{gate.runName}</TruncatedText>
            <span>&middot;</span>
            <Link
              to={`/runs/${gate.runId}`}
              className="inline-flex items-center gap-1 text-primary hover:underline"
            >
              View Run
              <ExternalLink className="h-3 w-3" />
            </Link>
          </div>
        </div>
        {waitingDuration && (
          <div className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
            <Clock className="h-3.5 w-3.5" />
            <span>Waiting {waitingDuration}</span>
          </div>
        )}
      </div>

      <Separator className="my-3" />

      {isEscalation ? (
        <EscalationGatePanel
          runId={gate.runId}
          escalation={gate.escalation}
          requiredArtifacts={gate.requiredArtifacts}
          decisionOptions={options}
          guidance={feedback}
          onGuidanceChange={setFeedback}
          onConfirm={handleSubmit}
          isPending={signalMutation.isPending || isLiveChat}
          readOnly={!canOperate}
        />
      ) : (
        <>
          {/* Predecessor outputs */}
          {gate.predecessorOutputs.length > 0 && (
            <>
              <div className="space-y-2">
                <button
                  type="button"
                  onClick={() => setOutputExpanded(!outputExpanded)}
                  className="flex w-full items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground/80"
                >
                  {outputExpanded ? (
                    <ChevronDown className="h-4 w-4" />
                  ) : (
                    <ChevronRight className="h-4 w-4" />
                  )}
                  Previous Step Output ({gate.predecessorOutputs.length})
                </button>
                {outputExpanded &&
                  gate.predecessorOutputs.map((pred, i) => (
                    <div key={pred.templateNodeId} className="space-y-1">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-medium text-muted-foreground">{pred.label}</span>
                        {pred.result && (
                          <Button
                            variant="ghost"
                            size="icon-xs"
                            onClick={() => setExpandedPredIdx(i)}
                            aria-label="Expand predecessor output"
                          >
                            <Maximize2 className="h-3.5 w-3.5" />
                          </Button>
                        )}
                      </div>
                      {pred.result ? (
                        <>
                          <MarkdownViewer content={pred.result} maxHeight="max-h-48" />
                          <PredecessorOutputDialog
                            nodeLabel={pred.label}
                            resultContent={pred.result}
                            open={expandedPredIdx === i}
                            onOpenChange={(open) => {
                              if (!open) setExpandedPredIdx(null);
                            }}
                          />
                        </>
                      ) : (
                        <p className="text-xs italic text-muted-foreground">No output available</p>
                      )}
                      {pred.nodeExecutionId && gate.requiredArtifacts == null && (
                        <ArtifactBrowser runId={gate.runId} execId={pred.nodeExecutionId} />
                      )}
                    </div>
                  ))}
              </div>
              <Separator className="my-3" />
            </>
          )}

          {gate.requiredArtifacts != null && gate.requiredArtifacts.length > 0 && (
            <>
              <ArtifactList runId={gate.runId} groups={gate.requiredArtifacts} />
              <Separator className="my-3" />
            </>
          )}

          {gate.candidateBreakdown != null && (
            <>
              <RoadmapCandidateBreakdown value={editedCandidates} onChange={setEditedCandidates} />
              <Separator className="my-3" />
            </>
          )}
        </>
      )}

      {/* Live Chat */}
      <LiveChatPanel
        runId={gate.runId}
        nodeExecId={gate.nodeExecutionId}
        nodeLabel={gate.nodeLabel}
      />

      <Separator className="my-3" />

      {/* Feedback + Actions */}
      {!isEscalation && canOperate && (
        <div className="space-y-3">
          <Textarea
            data-testid="gate-card-feedback"
            placeholder="Provide feedback for the AI agent..."
            value={feedback}
            onChange={(e) => setFeedback(e.target.value)}
            disabled={signalMutation.isPending || isLiveChat}
            className="min-h-[60px]"
          />
          <FileUploadZone onFilesChange={setAttachmentFiles} disabled={signalMutation.isPending} />
          <DecisionButtons
            options={options}
            onSubmit={handleSubmit}
            isPending={signalMutation.isPending || isLiveChat}
            feedback={feedback}
            testIdPrefix="gate-card"
          />
        </div>
      )}
    </div>
  );
}
