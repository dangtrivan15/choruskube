import { useState } from "react";
import { Pause, Play, XCircle, Pencil, Check, X } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { usePauseRun, useResumeRun, useCancelRun, useRenameRun } from "@/hooks/useRuns";
import { statusBadgeClass } from "@/lib/statusColors";
import Authorized from "@/components/Authorized";
import AutopilotRunBadge from "./AutopilotRunBadge";
import type { RunResponse } from "@/lib/types";

interface RunHeaderProps {
  run: RunResponse;
}

function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

export default function RunHeader({ run }: RunHeaderProps) {
  const pauseMutation = usePauseRun(run.id);
  const resumeMutation = useResumeRun(run.id);
  const cancelMutation = useCancelRun(run.id);
  const renameMutation = useRenameRun(run.id);

  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState("");

  const isTerminal = ["completed", "failed", "cancelled"].includes(run.status);

  function startEditing() {
    setEditValue(run.name ?? "");
    setIsEditing(true);
  }

  function cancelEditing() {
    setIsEditing(false);
    setEditValue("");
  }

  function saveRename() {
    const trimmed = editValue.trim();
    if (trimmed && trimmed !== run.name) {
      renameMutation.mutate(trimmed);
    }
    setIsEditing(false);
  }

  function handleKeyDown(e: React.KeyboardEvent) {
    if (e.key === "Enter") saveRename();
    if (e.key === "Escape") cancelEditing();
  }

  return (
    <div className="flex flex-col gap-2 border-b px-4 py-3 md:flex-row md:items-center md:justify-between">
      <div className="flex items-center gap-3">
        <div>
          {isEditing ? (
            <div className="flex items-center gap-1">
              <Input
                type="text"
                value={editValue}
                onChange={(e) => setEditValue(e.target.value)}
                onKeyDown={handleKeyDown}
                maxLength={30}
                autoFocus
                className="h-auto px-2 py-0.5 text-sm font-semibold"
              />
              <Button variant="ghost" size="icon" className="size-6" onClick={saveRename}>
                <Check className="size-3.5" />
              </Button>
              <Button variant="ghost" size="icon" className="size-6" onClick={cancelEditing}>
                <X className="size-3.5" />
              </Button>
            </div>
          ) : (
            <div className="flex items-center gap-1.5">
              <h1 data-testid="run-header-title" className="text-base font-semibold leading-tight">
                {run.name ?? run.templateName}
              </h1>
              <Authorized require="canOperate">
                <button
                  onClick={startEditing}
                  className="text-muted-foreground hover:text-foreground transition-colors"
                  aria-label="Rename run"
                >
                  <Pencil className="size-3" />
                </button>
              </Authorized>
            </div>
          )}
          <p className="mt-0.5 font-mono text-xs text-muted-foreground">
            {run.name ? run.templateName + " \u00B7 " : ""}{shortId(run.id)}
          </p>
        </div>
        <AutopilotRunBadge autopilotId={run.autopilotId} />
        <Badge data-testid="run-header-status" className={statusBadgeClass(run.status)}>
          {run.status.replace(/_/g, " ")}
        </Badge>
      </div>

      {!isTerminal && (
        <Authorized require="canOperate">
          <div className="flex items-center gap-2">
            {run.status === "running" && (
              <Button
                data-testid="run-pause-button"
                variant="outline"
                size="sm"
                onClick={() => pauseMutation.mutate()}
                disabled={pauseMutation.isPending}
              >
                <Pause className="size-3.5" data-icon="inline-start" />
                Pause
              </Button>
            )}

            {run.status === "paused" && (
              <Button
                data-testid="run-resume-button"
                variant="outline"
                size="sm"
                onClick={() => resumeMutation.mutate()}
                disabled={resumeMutation.isPending}
              >
                <Play className="size-3.5" data-icon="inline-start" />
                Resume
              </Button>
            )}

            <Button
              data-testid="run-cancel-button"
              variant="destructive"
              size="sm"
              onClick={() => cancelMutation.mutate()}
              disabled={cancelMutation.isPending}
            >
              <XCircle className="size-3.5" data-icon="inline-start" />
              Cancel
            </Button>
          </div>
        </Authorized>
      )}
    </div>
  );
}
