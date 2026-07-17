import { useState } from "react";
import { Maximize2 } from "lucide-react";
import { Link } from "react-router";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import PromptViewerDialog from "./PromptViewerDialog";
import type { RunResponse } from "@/lib/types";

interface RunMetaBarProps {
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

export default function RunMetaBar({ run }: RunMetaBarProps) {
  const task = run.task;
  const promptText = run.promptText;
  const [promptDialogOpen, setPromptDialogOpen] = useState(false);

  if (!task && !promptText && !run.softwareProject) return null;

  return (
    <div
      data-testid="run-meta-bar"
      className="flex flex-col gap-3 border-b px-4 py-3 text-sm"
    >
      {promptText && (
        <div className="space-y-1.5" data-testid="run-meta-bar-prompt">
          <div className="flex items-center justify-between">
            <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Feature Request
            </h4>
            <Button
              variant="ghost"
              size="icon-xs"
              onClick={() => setPromptDialogOpen(true)}
              aria-label="Expand feature request"
              data-testid="run-meta-bar-prompt-expand"
            >
              <Maximize2 className="h-3.5 w-3.5" />
            </Button>
          </div>
          <MarkdownViewer content={promptText} maxHeight="max-h-32" />
          <PromptViewerDialog
            promptText={promptText}
            open={promptDialogOpen}
            onOpenChange={setPromptDialogOpen}
          />
        </div>
      )}
      {run.softwareProject && (
        <div className="flex items-center gap-1.5" data-testid="run-meta-bar-software-project">
          <span className="text-muted-foreground">Software Project:</span>
          <span className="font-medium">{run.softwareProject.name}</span>
        </div>
      )}
      {task && (
        <div className="flex flex-wrap items-center gap-x-6 gap-y-1.5">
          <div className="flex items-center gap-1.5">
            <span className="text-muted-foreground">Roadmap:</span>
            <Link
              to={`/tasks/${task.id}`}
              className="font-medium text-primary hover:underline"
              data-testid="run-meta-bar-task-link"
            >
              {task.title}
            </Link>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="text-muted-foreground">Status:</span>
            <span data-testid="run-meta-bar-status">{statusChip(task.status)}</span>
          </div>
        </div>
      )}
    </div>
  );
}
