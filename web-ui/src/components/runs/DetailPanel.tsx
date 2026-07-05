import { useState } from "react";
import { Clock, AlertCircle, CheckCircle, Info, Maximize2, RotateCcw, MessageCircle, ChevronLeft } from "lucide-react";
import { format } from "date-fns";
import type { RunResponse, NodeExecutionResponse, SnapshotNode } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import { statusBadgeClass } from "@/lib/statusColors";
import { useRetryNode } from "@/hooks/useRuns";
import Authorized from "@/components/Authorized";
import ExecutionLogs from "./ExecutionLogs";
import HumanGatePanel from "./HumanGatePanel";
import LiveChatPanel from "./LiveChatPanel";
import ArtifactBrowser from "./ArtifactBrowser";
import ResultViewerDialog from "./ResultViewerDialog";
import PredecessorOutputDialog from "./PredecessorOutputDialog";

interface DetailPanelProps {
  run: RunResponse;
  nodeId: string; // template_node_id from the snapshot
  onBackToRunMeta?: () => void; // optional; when provided, renders back button
}

function findSnapshotNode(
  run: RunResponse,
  nodeId: string
): SnapshotNode | undefined {
  return run.graphSnapshot?.nodes.find((n) => n.template_node_id === nodeId);
}

function findLatestExecution(
  run: RunResponse,
  nodeId: string
): NodeExecutionResponse | undefined {
  const executions = run.nodeExecutions
    .filter((ne) => ne.templateNodeId === nodeId)
    .sort((a, b) => b.iteration - a.iteration);
  return executions[0];
}

function parseLoopGroup(node: SnapshotNode): string | null {
  const overrides = node.config_overrides;
  if (!overrides) return null;
  const loopGroup = overrides.loop_group;
  return typeof loopGroup === "string" ? loopGroup : null;
}

function findPredecessorOutputs(
  run: RunResponse,
  nodeId: string
): { nodeLabel: string; result: string | null; execId: string | null }[] {
  if (!run.graphSnapshot) return [];
  const predecessorNodeIds = [
    ...new Set(
      run.graphSnapshot.edges
        .filter((e) => e.target_node_id === nodeId)
        .map((e) => e.source_node_id)
    ),
  ];

  return predecessorNodeIds.map((predId) => {
    const predNode = run.graphSnapshot!.nodes.find(
      (n) => n.template_node_id === predId
    );
    const predExec = run.nodeExecutions
      .filter((ne) => ne.templateNodeId === predId)
      .sort((a, b) => b.iteration - a.iteration)[0];
    return {
      nodeLabel: predNode?.label ?? predId,
      result: predExec?.result ?? null,
      execId: predExec?.id ?? null,
    };
  });
}

/**
 * Outgoing edge conditions from the given template node. These are the valid
 * decision values for a human gate and drive the action buttons. Matches the
 * server's `PendingGateResponse.decisionOptions` computation so the two UI
 * surfaces (run detail + /approvals) stay aligned with the validator.
 */
function findDecisionOptions(run: RunResponse, nodeId: string): string[] {
  if (!run.graphSnapshot) return [];
  return run.graphSnapshot.edges
    .filter((e) => e.source_node_id === nodeId && e.condition != null)
    .map((e) => e.condition as string);
}

/**
 * Pick the decision string that triggered the current gate. The trigger is the
 * decision on the most-recent completed predecessor execution (e.g. the latest
 * Spec Review iteration before Approve Spec & Plan fires). Used to render
 * trigger-aware UI variants in the human gate.
 */
function findTriggerDecision(run: RunResponse, nodeId: string): string | null {
  if (!run.graphSnapshot) return null;
  const predecessorNodeIds = run.graphSnapshot.edges
    .filter((e) => e.target_node_id === nodeId)
    .map((e) => e.source_node_id);
  const predExecs = run.nodeExecutions
    .filter((ne) => predecessorNodeIds.includes(ne.templateNodeId))
    .filter((ne) => ne.decision != null)
    .sort((a, b) => {
      const at = a.completedAt ? new Date(a.completedAt).getTime() : 0;
      const bt = b.completedAt ? new Date(b.completedAt).getTime() : 0;
      return bt - at;
    });
  return predExecs[0]?.decision ?? null;
}

function StatusIcon({ status }: { status: string }) {
  switch (status) {
    case "completed":
      return <CheckCircle className="h-4 w-4 text-status-success" />;
    case "failed":
      return <AlertCircle className="h-4 w-4 text-status-error" />;
    case "running":
      return <Clock className="h-4 w-4 animate-pulse text-status-info" />;
    case "live_chat":
      return <MessageCircle className="h-4 w-4 animate-pulse text-status-accent" />;
    default:
      return <Info className="h-4 w-4 text-muted-foreground" />;
  }
}

/* ---------- Sub-panels ---------- */

function PendingNodeInfo({ node }: { node: SnapshotNode }) {
  return (
    <div className="space-y-3">
      <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        Node Definition
      </h4>

      <div className="space-y-2 text-sm">
        <div className="flex justify-between">
          <span className="text-muted-foreground">Executor type</span>
          <Badge variant="outline">{node.executor_type}</Badge>
        </div>

        {node.timeout_seconds != null && (
          <div className="flex justify-between">
            <span className="text-muted-foreground">Timeout</span>
            <span>{node.timeout_seconds}s</span>
          </div>
        )}

        {node.is_entrypoint && (
          <div className="flex justify-between">
            <span className="text-muted-foreground">Entrypoint</span>
            <Badge variant="secondary">Yes</Badge>
          </div>
        )}
      </div>

      {node.prompt_template && (
        <>
          <Separator />
          <div className="space-y-1.5">
            <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Prompt Template
            </h4>
            <pre className="max-h-48 overflow-auto rounded-md border bg-muted/30 p-3 text-xs whitespace-pre-wrap">
              {node.prompt_template}
            </pre>
          </div>
        </>
      )}

      {node.config_overrides && Object.keys(node.config_overrides).length > 0 && (
        <>
          <Separator />
          <div className="space-y-1.5">
            <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Config Overrides
            </h4>
            <pre className="max-h-32 overflow-auto rounded-md border bg-muted/30 p-3 text-xs whitespace-pre-wrap">
              {JSON.stringify(node.config_overrides, null, 2)}
            </pre>
          </div>
        </>
      )}
    </div>
  );
}

function RetryButton({ runId, nodeExecId }: { runId: string; nodeExecId: string }) {
  const retryMutation = useRetryNode(runId);
  return (
    <Button
      variant="outline"
      size="sm"
      onClick={() => retryMutation.mutate(nodeExecId)}
      disabled={retryMutation.isPending}
      className="w-full"
    >
      <RotateCcw className="size-3.5" data-icon="inline-start" />
      {retryMutation.isPending ? "Retrying..." : "Retry this node"}
    </Button>
  );
}

function CompletedPanel({
  run,
  exec,
  nodeLabel,
  predecessorOutputs = [],
}: {
  run: RunResponse;
  exec: NodeExecutionResponse;
  nodeLabel: string;
  predecessorOutputs?: { nodeLabel: string; result: string | null }[];
}) {
  const [resultDialogOpen, setResultDialogOpen] = useState(false);
  const [expandedPredIdx, setExpandedPredIdx] = useState<number | null>(null);
  return (
    <div className="space-y-4">
      {/* Execution info */}
      <div className="space-y-2 text-sm">
        {exec.startedAt && (
          <div className="flex justify-between">
            <span className="text-muted-foreground">Started</span>
            <span>{format(new Date(exec.startedAt), "MMM d, HH:mm:ss")}</span>
          </div>
        )}
        {exec.completedAt && (
          <div className="flex justify-between">
            <span className="text-muted-foreground">Completed</span>
            <span>{format(new Date(exec.completedAt), "MMM d, HH:mm:ss")}</span>
          </div>
        )}
        {exec.podName && (
          <div className="flex justify-between">
            <span className="text-muted-foreground">Pod</span>
            <span className="font-mono text-xs">{exec.podName}</span>
          </div>
        )}
      </div>

      <Separator />

      {/* Predecessor output (for human gates) */}
      {predecessorOutputs.length > 0 && (
        <>
          {predecessorOutputs.map((pred, i) => (
            <div key={i} className="space-y-1.5">
              <div className="flex items-center justify-between">
                <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {predecessorOutputs.length > 1 ? `Input from ${pred.nodeLabel}` : "Previous Step Output"}
                </h4>
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
            </div>
          ))}
          <Separator />
        </>
      )}

      {/* Result or Error */}
      {exec.status === "failed" && exec.errorMessage && (
        <div className="space-y-1.5" data-testid="detail-node-error">
          <h4 className="text-xs font-medium uppercase tracking-wide text-status-error">
            Error
          </h4>
          <pre className="max-h-32 overflow-auto rounded-md border border-status-error/30 bg-status-error/10 p-3 text-xs whitespace-pre-wrap text-status-error">
            {exec.errorMessage}
          </pre>
        </div>
      )}

      {exec.status === "failed" && run.status === "awaiting_retry" && (
        <Authorized require="canOperate">
          <RetryButton runId={run.id} nodeExecId={exec.id} />
        </Authorized>
      )}

      {exec.result && (
        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Result
            </h4>
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={() => setResultDialogOpen(true)}
              aria-label="Expand result"
            >
              <Maximize2 className="h-3.5 w-3.5" />
            </Button>
          </div>
          <MarkdownViewer content={exec.result} maxHeight="max-h-48" />
          <ResultViewerDialog
            nodeLabel={nodeLabel}
            resultContent={exec.result}
            open={resultDialogOpen}
            onOpenChange={setResultDialogOpen}
          />
        </div>
      )}

      {/* Artifacts */}
      <ArtifactBrowser runId={run.id} execId={exec.id} />

      <Separator />

      {/* Logs */}
      <div className="space-y-1.5">
        <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          Logs
        </h4>
        <ExecutionLogs runId={run.id} nodeExecId={exec.id} isActive={false} />
      </div>
    </div>
  );
}

/* ---------- Main component ---------- */

export default function DetailPanel({ run, nodeId, onBackToRunMeta }: DetailPanelProps) {
  const snapshotNode = findSnapshotNode(run, nodeId);
  const latestExec = findLatestExecution(run, nodeId);

  if (!snapshotNode) {
    return (
      <div className="p-4 text-sm text-muted-foreground">
        Node not found in graph snapshot.
      </div>
    );
  }

  const status = latestExec?.status ?? "pending";
  const loopGroup = parseLoopGroup(snapshotNode);

  return (
    <div data-testid="detail-panel" className="flex h-full flex-col overflow-hidden">
      {/* Header */}
      <div className="border-b px-4 py-3">
        {onBackToRunMeta && (
          <button
            onClick={onBackToRunMeta}
            data-testid="detail-panel-back-button"
            className="mb-2 flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
            aria-label="Back to run info"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            Run info
          </button>
        )}
        <div className="flex items-center gap-2">
          <StatusIcon status={status} />
          <h2 data-testid="detail-node-label" className="text-sm font-semibold">{snapshotNode.label}</h2>
        </div>
        <div className="mt-1.5 flex items-center gap-2">
          <Badge data-testid="detail-node-status" className={statusBadgeClass(status)}>{status}</Badge>
          {latestExec && latestExec.iteration > 1 && (
            <Badge variant="outline">Iteration {latestExec.iteration}</Badge>
          )}
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-4">
        {status === "awaiting_human" && latestExec && (
          <HumanGatePanel
            runId={run.id}
            nodeExecId={latestExec.id}
            loopGroup={loopGroup}
            nodeLabel={snapshotNode.label}
            iteration={latestExec.iteration}
            predecessorOutputs={
              latestExec.requiredArtifacts != null
                ? undefined
                : findPredecessorOutputs(run, nodeId)
            }
            requiredArtifacts={latestExec.requiredArtifacts ?? null}
            nodeResult={latestExec.result}
            triggerDecision={findTriggerDecision(run, nodeId)}
            decisionOptions={findDecisionOptions(run, nodeId)}
          />
        )}

        {status === "live_chat" && latestExec && (
          <div className="space-y-4">
            <LiveChatPanel
              runId={run.id}
              nodeExecId={latestExec.id}
              nodeLabel={snapshotNode.label}
            />
          </div>
        )}

        {status === "running" && latestExec && (
          <div className="space-y-4">
            <div className="space-y-2 text-sm">
              {latestExec.startedAt && (
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Started</span>
                  <span>
                    {format(new Date(latestExec.startedAt), "MMM d, HH:mm:ss")}
                  </span>
                </div>
              )}
              {latestExec.podName && (
                <div className="flex justify-between">
                  <span className="text-muted-foreground">Pod</span>
                  <span className="font-mono text-xs">{latestExec.podName}</span>
                </div>
              )}
            </div>
            <Separator />
            <div className="space-y-1.5">
              <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                Live Logs
              </h4>
              <ExecutionLogs
                runId={run.id}
                nodeExecId={latestExec.id}
                isActive={true}
              />
            </div>
          </div>
        )}

        {(status === "completed" || status === "failed") && latestExec && (
          <CompletedPanel
            run={run}
            exec={latestExec}
            nodeLabel={snapshotNode.label}
            predecessorOutputs={
              snapshotNode.executor_type === "human"
                ? findPredecessorOutputs(run, nodeId)
                : undefined
            }
          />
        )}

        {status === "pending" && <PendingNodeInfo node={snapshotNode} />}
      </div>
    </div>
  );
}
