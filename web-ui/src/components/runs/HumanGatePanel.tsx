import { useState } from "react";
import FileUploadZone from "./FileUploadZone";
import { ChevronDown, ChevronRight, Maximize2, AlertTriangle, HelpCircle, Lightbulb } from "lucide-react";
import { useSignalNode } from "@/hooks/useRuns";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Separator } from "@/components/ui/separator";
import { Badge } from "@/components/ui/badge";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import { usePermission } from "@/hooks/usePermission";
import ReviewHistory from "./ReviewHistory";
import ArtifactBrowser from "./ArtifactBrowser";
import ArtifactList from "./ArtifactList";
import PredecessorOutputDialog from "./PredecessorOutputDialog";
import LiveChatPanel from "./LiveChatPanel";
import DecisionButtons, { LEGACY_DECISION_OPTIONS } from "./DecisionButtons";
import { parseGateTrigger, type GateTrigger } from "@/lib/decisions";
import type { ResolvedArtifactGroup } from "@/lib/types";

interface PredecessorOutput {
  nodeLabel: string;
  result: string | null;
  execId: string | null;
}

interface HumanGatePanelProps {
  runId: string;
  nodeExecId: string;
  loopGroup: string | null;
  nodeLabel: string;
  iteration: number;
  predecessorOutputs?: PredecessorOutput[];
  nodeResult?: string | null;
  requiredArtifacts?: ResolvedArtifactGroup[] | null;
  /** The triggering reviewer's decision string, e.g. `need_human_decision:iteration_cap`. */
  triggerDecision?: string | null;
  /**
   * Valid decisions for this gate — outgoing edge conditions from the template
   * node. Drives the action buttons. Falls back to legacy approve/reject when
   * empty (e.g. snapshot missing).
   */
  decisionOptions?: readonly string[];
}

function TriggerBanner({ trigger }: { trigger: GateTrigger }) {
  if (trigger.kind === "approved") return null;
  if (trigger.kind === "iteration_cap") {
    return (
      <div className="flex gap-3 rounded-md border border-status-warning/40 bg-status-warning/10 p-3">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-status-warning" />
        <div className="space-y-0.5 text-sm">
          <p className="font-semibold">Iteration cap reached</p>
          <p className="text-muted-foreground">
            The reviewer could not converge after the maximum iterations. Unresolved flaws
            are listed in the artifact below — review them and decide how to proceed.
          </p>
        </div>
      </div>
    );
  }
  if (trigger.kind === "uncertainty") {
    return (
      <div className="flex gap-3 rounded-md border border-status-info/40 bg-status-info/10 p-3">
        <HelpCircle className="mt-0.5 h-4 w-4 shrink-0 text-status-info" />
        <div className="space-y-0.5 text-sm">
          <p className="font-semibold">Reviewer uncertain about fix</p>
          <p className="text-muted-foreground">
            The reviewer found a flaw but was not confident in the correct fix. Review the
            description below and provide direction.
          </p>
        </div>
      </div>
    );
  }
  // alternative_proposal
  return (
    <div className="flex gap-3 rounded-md border border-status-accent/40 bg-status-accent/10 p-3">
      <Lightbulb className="mt-0.5 h-4 w-4 shrink-0 text-status-accent" />
      <div className="space-y-0.5 text-sm">
        <p className="font-semibold">Alternative design proposed</p>
        <p className="text-muted-foreground">
          The reviewer believes a fundamentally different approach is better. Compare the
          current spec with the alternative below and decide.
        </p>
      </div>
    </div>
  );
}

export default function HumanGatePanel({
  runId,
  nodeExecId,
  loopGroup,
  nodeLabel,
  iteration,
  predecessorOutputs = [],
  nodeResult,
  requiredArtifacts,
  triggerDecision,
  decisionOptions,
}: HumanGatePanelProps) {
  const [feedback, setFeedback] = useState("");
  const [outputExpanded, setOutputExpanded] = useState(true);
  const [expandedPredIdx, setExpandedPredIdx] = useState<number | null>(null);
  const [attachmentFiles, setAttachmentFiles] = useState<File[]>([]);
  const signalMutation = useSignalNode(runId);
  const { canOperate } = usePermission();

  const trigger = parseGateTrigger(triggerDecision);
  // TriggerBanner is shown for gates whose edge set includes the v23 rereview/redraft
  // actions — the banner explains *why* a non-approval flow exists for this gate.
  const options = decisionOptions && decisionOptions.length > 0 ? decisionOptions : LEGACY_DECISION_OPTIONS;
  const isV23Gate = options.includes("rereview") || options.includes("redraft");

  function handleSubmit(decision: string) {
    // For rereview/redraft, attach the typed guidance as `human_guidance.md` so the
    // next-iteration agent reads it from /workspace/in/<gate_label>/human_guidance.md.
    // The existing `feedback` channel still goes to the signal payload (audit log).
    let files = attachmentFiles;
    if ((decision === "rereview" || decision === "redraft") && feedback.trim()) {
      const guidanceFile = new File([feedback], "human_guidance.md", { type: "text/markdown" });
      files = [guidanceFile, ...attachmentFiles];
    }
    signalMutation.mutate(
      { nodeExecId, decision, feedback, files },
      {
        onSuccess: () => {
          setFeedback("");
          setAttachmentFiles([]);
        },
      }
    );
  }

  return (
    <div className="space-y-4">
      {/* Node header */}
      <div>
        <h3 className="text-sm font-semibold">{nodeLabel}</h3>
        <div className="mt-1 flex items-center gap-2">
          <Badge variant="outline">Iteration {iteration}</Badge>
          <Badge className="bg-status-warning/15 text-status-warning">
            Awaiting Review
          </Badge>
        </div>
      </div>

      {isV23Gate && trigger.kind !== "approved" && (
        <TriggerBanner trigger={trigger} />
      )}

      <Separator />

      {/* Required artifacts (new mode) or previous step output (legacy mode) */}
      {requiredArtifacts != null ? (
        requiredArtifacts.length > 0 && (
          <>
            <ArtifactList runId={runId} groups={requiredArtifacts} />
            <Separator />
          </>
        )
      ) : (
        predecessorOutputs.length > 0 && (
          <>
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => setOutputExpanded(!outputExpanded)}
                  className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground/80"
                >
                  {outputExpanded ? (
                    <ChevronDown className="h-4 w-4" />
                  ) : (
                    <ChevronRight className="h-4 w-4" />
                  )}
                  Previous Step Output
                </button>
                {outputExpanded && predecessorOutputs.length === 1 && predecessorOutputs[0].result && (
                  <Button
                    variant="ghost"
                    size="icon-xs"
                    onClick={() => setExpandedPredIdx(0)}
                    aria-label="Expand predecessor output"
                  >
                    <Maximize2 className="h-3.5 w-3.5" />
                  </Button>
                )}
              </div>
              {outputExpanded &&
                predecessorOutputs.map((pred, i) => (
                  <div key={i} className="space-y-2">
                    {predecessorOutputs.length > 1 && (
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-medium text-muted-foreground">
                          {pred.nodeLabel}
                        </span>
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
                    )}
                    {pred.result ? (
                      <>
                        <MarkdownViewer content={pred.result} maxHeight="max-h-72" />
                        <PredecessorOutputDialog
                          nodeLabel={pred.nodeLabel}
                          resultContent={pred.result}
                          open={expandedPredIdx === i}
                          onOpenChange={(open) => {
                            if (!open) setExpandedPredIdx(null);
                          }}
                        />
                      </>
                    ) : (
                      <p className="text-sm italic text-muted-foreground">
                        No output available
                      </p>
                    )}
                    {pred.execId && (
                      <ArtifactBrowser runId={runId} execId={pred.execId} />
                    )}
                  </div>
                ))}
            </div>
            <Separator />
          </>
        )
      )}

      {/* Review history */}
      <ReviewHistory runId={runId} loopGroup={loopGroup} />

      <Separator />

      {/* Chat transcript (from completed live chat) */}
      {nodeResult && (
        <>
          <div className="space-y-1.5">
            <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Chat Transcript
            </h4>
            <MarkdownViewer content={nodeResult} maxHeight="max-h-48" />
          </div>
          <Separator />
        </>
      )}

      {canOperate && (
        <>
          {/* Feedback form */}
          <div className="space-y-2">
            <label
              htmlFor="feedback"
              className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
            >
              Feedback
            </label>
            <Textarea
              id="feedback"
              data-testid="gate-feedback-input"
              placeholder="Provide feedback for the AI agent..."
              value={feedback}
              onChange={(e) => setFeedback(e.target.value)}
              disabled={signalMutation.isPending}
            />
          </div>

          <FileUploadZone onFilesChange={setAttachmentFiles} disabled={signalMutation.isPending} />

          <DecisionButtons
            options={options}
            onSubmit={handleSubmit}
            isPending={signalMutation.isPending}
            feedback={feedback}
            trigger={trigger}
            testIdPrefix="gate"
          />
        </>
      )}

      {/* Live Chat */}
      <LiveChatPanel runId={runId} nodeExecId={nodeExecId} nodeLabel={nodeLabel} />
    </div>
  );
}
