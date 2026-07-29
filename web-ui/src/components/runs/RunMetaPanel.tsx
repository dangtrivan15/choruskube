import { useState } from "react";
import { Maximize2 } from "lucide-react";
import { Link } from "react-router";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import PromptViewerDialog from "./PromptViewerDialog";
import PullRequestLinks from "./PullRequestLinks";
import type { RunResponse } from "@/lib/types";

interface RunMetaPanelProps {
  run: RunResponse;
}

function statusChip(status: "backlog" | "in_progress" | "done") {
  switch (status) {
    case "backlog":
      return <Badge variant="outline">backlog</Badge>;
    case "in_progress":
      return <Badge variant="secondary">in progress</Badge>;
    case "done":
      return <Badge variant="default">done</Badge>;
  }
}

export default function RunMetaPanel({ run }: RunMetaPanelProps) {
  const [promptDialogOpen, setPromptDialogOpen] = useState(false);

  const hasMetadata =
    !!run.promptText ||
    !!run.softwareProject ||
    !!run.task ||
    (run.pullRequests?.length ?? 0) > 0;

  return (
    <div data-testid="run-meta-panel" className="flex h-full flex-col overflow-hidden">
      <div className="border-b px-4 py-3">
        <h2 className="text-sm font-semibold">Run Info</h2>
      </div>
      <div className="flex-1 overflow-y-auto p-4 space-y-4">
        {!hasMetadata && (
          <p className="text-sm text-muted-foreground">No run metadata available.</p>
        )}
        {run.promptText && (
          <div className="space-y-1.5" data-testid="run-meta-panel-prompt">
            <div className="flex items-center justify-between">
              <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                Feature Request
              </h4>
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={() => setPromptDialogOpen(true)}
                aria-label="Expand feature request"
                data-testid="run-meta-panel-prompt-expand"
              >
                <Maximize2 className="h-3.5 w-3.5" />
              </Button>
            </div>
            <MarkdownViewer content={run.promptText} maxHeight="max-h-48" />
            <PromptViewerDialog
              promptText={run.promptText}
              open={promptDialogOpen}
              onOpenChange={setPromptDialogOpen}
            />
          </div>
        )}
        {run.softwareProject && (
          <div className="flex items-center gap-1.5" data-testid="run-meta-panel-software-project">
            <span className="text-muted-foreground">Software Project:</span>
            <span className="font-medium">{run.softwareProject.name}</span>
          </div>
        )}
        {run.task && (
          <div className="flex flex-wrap items-center gap-x-6 gap-y-1.5">
            <div className="flex items-center gap-1.5">
              <span className="text-muted-foreground">Roadmap:</span>
              {run.task.epicId && (
                <span className="flex items-center gap-1.5" data-testid="run-meta-panel-breadcrumb">
                  <Link
                    to={`/roadmap/epics/${run.task.epicId}`}
                    className="text-primary hover:underline"
                    data-testid="run-meta-panel-epic-link"
                  >
                    {run.task.epicTitle}
                  </Link>
                  {run.task.storyId && (
                    <>
                      <span className="text-muted-foreground">/</span>
                      <Link
                        to={`/roadmap/epics/${run.task.epicId}/stories/${run.task.storyId}`}
                        className="text-primary hover:underline"
                        data-testid="run-meta-panel-story-link"
                      >
                        {run.task.storyTitle}
                      </Link>
                    </>
                  )}
                  <span className="text-muted-foreground">/</span>
                </span>
              )}
              <Link
                to={`/tasks/${run.task.id}`}
                className="font-medium text-primary hover:underline"
                data-testid="run-meta-panel-task-link"
              >
                {run.task.title}
              </Link>
            </div>
            <div className="flex items-center gap-1.5">
              <span className="text-muted-foreground">Status:</span>
              <span data-testid="run-meta-panel-status">{statusChip(run.task.status)}</span>
            </div>
          </div>
        )}
        {(run.pullRequests?.length ?? 0) > 0 && (
          <PullRequestLinks pullRequests={run.pullRequests} />
        )}
      </div>
    </div>
  );
}
