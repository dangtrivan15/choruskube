import { useRef, useState } from "react";
import { Pause, Play, XCircle, Pencil, Check, X, MoreVertical } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import TruncatedText from "@/components/ui/TruncatedText";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from "@/components/ui/dropdown-menu";
import { usePauseRun, useResumeRun, useCancelRun, useRenameRun } from "@/hooks/useRuns";
import { statusBadgeClass } from "@/lib/statusColors";
import Authorized from "@/components/Authorized";
import AutopilotRunBadge from "./AutopilotRunBadge";
import type { RunResponse } from "@/lib/types";

interface RunHeaderProps {
  run: RunResponse;
  /** Phone tier — a compact single row with lifecycle actions behind a "⋯" menu. */
  compact?: boolean;
}

function shortId(id: string): string {
  return id.length > 8 ? id.slice(0, 8) : id;
}

export default function RunHeader({ run, compact = false }: RunHeaderProps) {
  const pauseMutation = usePauseRun(run.id);
  const resumeMutation = useResumeRun(run.id);
  const cancelMutation = useCancelRun(run.id);
  const renameMutation = useRenameRun(run.id);

  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState("");
  // Set the instant "Rename" is chosen; read and cleared by the menu's `finalFocus` override
  // below. The menu would otherwise return focus to its trigger once it finishes closing,
  // which races the rename input's own `autoFocus` and (on a phone) dismisses the keyboard
  // the moment it appears.
  const pendingRenameRef = useRef(false);

  const isTerminal = ["completed", "failed", "cancelled"].includes(run.status);
  const title = run.name ?? run.templateName;

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

  const renameInput = isEditing && (
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
  );

  const statusBadge = (
    <Badge data-testid="run-header-status" className={statusBadgeClass(run.status)}>
      {run.status.replace(/_/g, " ")}
    </Badge>
  );

  if (compact) {
    return (
      <div className="flex flex-col gap-1 border-b px-3 py-2">
        <div className="flex items-center gap-2">
          {isEditing ? (
            <div className="min-w-0 flex-1">{renameInput}</div>
          ) : (
            <TruncatedText
              as="h1"
              data-testid="run-header-title"
              className="min-w-0 flex-1 text-base font-semibold leading-tight"
            >
              {title}
            </TruncatedText>
          )}
          {statusBadge}
          <Authorized require="canOperate">
            <DropdownMenu>
              <DropdownMenuTrigger
                data-testid="run-actions-menu-trigger"
                aria-label="Run actions"
                render={<Button variant="ghost" size="icon-sm" />}
              >
                <MoreVertical className="size-4" />
              </DropdownMenuTrigger>
              <DropdownMenuContent
                align="end"
                // The menu's default final-focus target is its trigger — which would blur the
                // rename input's `autoFocus` and (on a phone) dismiss the keyboard the instant it
                // appears. `pendingRenameRef` is set (and consumed) by the Rename item below, in
                // the same synchronous click that already moved focus into the input.
                finalFocus={() => {
                  if (pendingRenameRef.current) {
                    pendingRenameRef.current = false;
                    return false;
                  }
                  return true;
                }}
              >
                <DropdownMenuItem
                  data-testid="run-rename-menu-item"
                  onClick={() => {
                    pendingRenameRef.current = true;
                    startEditing();
                  }}
                >
                  Rename
                </DropdownMenuItem>
                {run.status === "running" && (
                  <DropdownMenuItem
                    data-testid="run-pause-button"
                    onClick={() => pauseMutation.mutate()}
                  >
                    <Pause className="size-3.5" />
                    Pause
                  </DropdownMenuItem>
                )}
                {run.status === "paused" && (
                  <DropdownMenuItem
                    data-testid="run-resume-button"
                    onClick={() => resumeMutation.mutate()}
                  >
                    <Play className="size-3.5" />
                    Resume
                  </DropdownMenuItem>
                )}
                {!isTerminal && (
                  <DropdownMenuItem
                    data-testid="run-cancel-button"
                    variant="destructive"
                    onClick={() => cancelMutation.mutate()}
                  >
                    <XCircle className="size-3.5" />
                    Cancel
                  </DropdownMenuItem>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          </Authorized>
        </div>
        <div className="flex items-center gap-1.5">
          <p className="font-mono text-xs text-muted-foreground">
            {run.name ? run.templateName + " · " : ""}{shortId(run.id)}
          </p>
          <AutopilotRunBadge autopilotId={run.autopilotId} />
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2 border-b px-4 py-3 md:flex-row md:items-center md:justify-between">
      <div className="flex items-center gap-3">
        <div className="min-w-0">
          {isEditing ? (
            renameInput
          ) : (
            <div className="flex items-center gap-1.5">
              <TruncatedText
                as="h1"
                data-testid="run-header-title"
                className="min-w-0 text-base font-semibold leading-tight"
              >
                {title}
              </TruncatedText>
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
            {run.name ? run.templateName + " · " : ""}{shortId(run.id)}
          </p>
        </div>
        <AutopilotRunBadge autopilotId={run.autopilotId} />
        {statusBadge}
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
