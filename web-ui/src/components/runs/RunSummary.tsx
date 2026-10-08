import { useState } from "react";
import { GitBranch, Info, Maximize2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import TruncatedText from "@/components/ui/TruncatedText";
import RoadmapBreadcrumb from "@/components/roadmap/RoadmapBreadcrumb";
import PullRequestLinks from "./PullRequestLinks";
import PromptViewerDialog from "./PromptViewerDialog";
import { formatNodeLabel } from "./DagNode";
import { firstLinePreview } from "@/lib/textPreview";
import { roadmapLevelMeta } from "@/lib/roadmapLevel";
import type { RunResponse } from "@/lib/types";

const EXPANDED_STORAGE_KEY = "run-summary-expanded";

/** The node a "Review …"/"Failed: …" shortcut points at — not a bare `SnapshotNode`, since the
 * strip/bar need to know which of the two attention kinds it is to choose the right wording. */
export interface AttentionNodeRef {
  templateNodeId: string;
  label: string;
  kind: "awaiting_human" | "failed";
}

function readStoredExpanded(): boolean {
  try {
    return localStorage.getItem(EXPANDED_STORAGE_KEY) === "true";
  } catch {
    return false;
  }
}

function writeStoredExpanded(expanded: boolean): void {
  try {
    localStorage.setItem(EXPANDED_STORAGE_KEY, expanded ? "true" : "false");
  } catch {
    // Storage full or unavailable — ignore, same as useResizable's width persistence.
  }
}

function taskStatusChip(status: "backlog" | "in_progress" | "done") {
  switch (status) {
    case "backlog":
      return <Badge variant="outline">backlog</Badge>;
    case "in_progress":
      return <Badge variant="secondary">in progress</Badge>;
    case "done":
      return <Badge variant="default">done</Badge>;
  }
}

function hasRunMetadata(run: RunResponse): boolean {
  return !!run.promptText || !!run.softwareProject || !!run.task || (run.pullRequests?.length ?? 0) > 0;
}

function attentionButtonLabel(attentionNode: AttentionNodeRef): string {
  const label = formatNodeLabel(attentionNode.label);
  return attentionNode.kind === "awaiting_human" ? `Review ${label}` : `Failed: ${label}`;
}

interface RunSummaryStripProps {
  run: RunResponse;
  attentionNode?: AttentionNodeRef | null;
  onSelectNode?: (nodeId: string) => void;
}

/** Tablet/desktop summary strip under the run header — request preview, project, roadmap
 * chain and PR links, always visible alongside the selected node's detail. */
export function RunSummaryStrip({ run, attentionNode, onSelectNode }: RunSummaryStripProps) {
  const [expanded, setExpanded] = useState(readStoredExpanded);
  const [promptDialogOpen, setPromptDialogOpen] = useState(false);

  const hasMetadata = hasRunMetadata(run);
  const hasAttentionButton = !!attentionNode && !!onSelectNode;

  if (!hasMetadata && !hasAttentionButton) return null;

  function toggleExpanded() {
    setExpanded((prev) => {
      const next = !prev;
      writeStoredExpanded(next);
      return next;
    });
  }

  return (
    <section data-testid="run-summary" data-variant="strip" className="border-b px-4 py-2 text-sm">
      <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1.5">
        {run.softwareProject && (
          <div className="flex min-w-0 items-center gap-1.5" data-testid="run-summary-software-project">
            <GitBranch className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <TruncatedText className="max-w-56 font-medium">{run.softwareProject.name}</TruncatedText>
          </div>
        )}
        {run.task && (
          <div className="flex min-w-0 max-w-full flex-1 items-center gap-2">
            <RoadmapBreadcrumb task={run.task} variant="inline" className="min-w-0 max-w-full flex-1" />
            <span data-testid="run-summary-task-status">{taskStatusChip(run.task.status)}</span>
          </div>
        )}
        <PullRequestLinks pullRequests={run.pullRequests} variant="inline" />
        {hasAttentionButton && (
          <Button
            data-testid="run-attention-button"
            variant="outline"
            size="sm"
            onClick={() => onSelectNode!(attentionNode!.templateNodeId)}
          >
            {attentionButtonLabel(attentionNode!)}
          </Button>
        )}
      </div>
      {run.promptText && (
        <div className="mt-1.5 flex min-w-0 items-start gap-2" data-testid="run-summary-prompt">
          <span className="mt-0.5 shrink-0 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Request
          </span>
          <div className="min-w-0 flex-1">
            {expanded ? (
              <MarkdownViewer content={run.promptText} maxHeight="max-h-48" />
            ) : (
              <p className="min-w-0 flex-1 truncate">{firstLinePreview(run.promptText)}</p>
            )}
          </div>
          <div className="flex shrink-0 items-center gap-1">
            <Button
              variant="ghost"
              size="sm"
              data-testid="run-summary-prompt-toggle"
              aria-expanded={expanded}
              onClick={toggleExpanded}
            >
              {expanded ? "Less" : "More"}
            </Button>
            <Button
              variant="ghost"
              size="icon-xs"
              data-testid="run-summary-prompt-expand"
              aria-label="Expand feature request"
              onClick={() => setPromptDialogOpen(true)}
            >
              <Maximize2 className="h-3.5 w-3.5" />
            </Button>
          </div>
          <PromptViewerDialog
            promptText={run.promptText}
            open={promptDialogOpen}
            onOpenChange={setPromptDialogOpen}
          />
        </div>
      )}
    </section>
  );
}

interface RunSummaryMobileBarProps {
  run: RunResponse;
  attentionNode?: AttentionNodeRef | null;
  onOpenInfo: () => void;
  onSelectNode: (nodeId: string) => void;
}

/** Phone one-line bar: a "Run info" sheet opener, a short context label, and (when a gate is
 * waiting or a node failed) the "Review …"/"Failed: …" shortcut. */
export function RunSummaryMobileBar({ run, attentionNode, onOpenInfo, onSelectNode }: RunSummaryMobileBarProps) {
  const taskMeta = run.task ? roadmapLevelMeta("task") : null;

  return (
    <div data-testid="run-summary-mobile-bar" className="flex items-center gap-2 border-b px-3 py-1.5">
      <Button variant="ghost" size="sm" data-testid="run-info-open-button" onClick={onOpenInfo}>
        <Info className="size-3.5" data-icon="inline-start" />
        Run info
      </Button>
      <div className="flex min-w-0 flex-1 items-center gap-1 text-xs text-muted-foreground">
        {run.task && taskMeta ? (
          <>
            <taskMeta.Icon className={`size-3.5 shrink-0 ${taskMeta.textClass}`} aria-hidden="true" />
            <TruncatedText className="min-w-0">{run.task.title}</TruncatedText>
          </>
        ) : run.softwareProject ? (
          <TruncatedText className="min-w-0">{run.softwareProject.name}</TruncatedText>
        ) : run.promptText ? (
          <TruncatedText className="min-w-0">{firstLinePreview(run.promptText)}</TruncatedText>
        ) : null}
      </div>
      {attentionNode && (
        <Button
          data-testid="run-attention-button"
          variant="outline"
          size="sm"
          className="min-w-0 max-w-[50%] shrink"
          aria-label={attentionButtonLabel(attentionNode)}
          onClick={() => onSelectNode(attentionNode.templateNodeId)}
        >
          <span className="min-w-0 truncate">{attentionButtonLabel(attentionNode)}</span>
        </Button>
      )}
    </div>
  );
}

interface RunSummaryDetailsProps {
  run: RunResponse;
}

/** Stacked "Run info" sheet body (phone) — full, untruncated titles and the request in full. */
export function RunSummaryDetails({ run }: RunSummaryDetailsProps) {
  const [promptDialogOpen, setPromptDialogOpen] = useState(false);

  const hasMetadata = hasRunMetadata(run);

  return (
    <div data-testid="run-summary" data-variant="details" className="space-y-4 p-4">
      {!hasMetadata && <p className="text-sm text-muted-foreground">No run metadata available.</p>}
      {run.promptText && (
        <div className="space-y-1.5" data-testid="run-summary-prompt">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Feature Request
            </h4>
            <Button
              variant="ghost"
              size="icon-xs"
              data-testid="run-summary-prompt-expand"
              aria-label="Expand feature request"
              onClick={() => setPromptDialogOpen(true)}
            >
              <Maximize2 className="h-3.5 w-3.5" />
            </Button>
          </div>
          <MarkdownViewer content={run.promptText} maxHeight="max-h-64" />
          <PromptViewerDialog
            promptText={run.promptText}
            open={promptDialogOpen}
            onOpenChange={setPromptDialogOpen}
          />
        </div>
      )}
      {run.softwareProject && (
        <div className="flex items-start gap-1.5" data-testid="run-summary-software-project">
          <span className="text-muted-foreground">Software Project:</span>
          <span className="break-words font-medium">{run.softwareProject.name}</span>
        </div>
      )}
      {run.task && (
        <div className="space-y-2">
          <RoadmapBreadcrumb task={run.task} variant="stacked" />
          <div className="flex items-center gap-1.5">
            <span className="text-muted-foreground">Status:</span>
            <span data-testid="run-summary-task-status">{taskStatusChip(run.task.status)}</span>
          </div>
        </div>
      )}
      {(run.pullRequests?.length ?? 0) > 0 && <PullRequestLinks pullRequests={run.pullRequests} />}
    </div>
  );
}
