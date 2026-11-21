import { useState } from "react";
import { useCloseTaskAsDelivered } from "@/hooks/useTasks";
import { apiErrorMessage } from "@/lib/api";
import type { TaskResponse } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Props {
  task: TaskResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** One pull request URL per line; blank lines dropped. The server validates and normalizes each. */
function parsePullRequestUrls(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/**
 * Close a Task whose work landed through pull requests made outside ChorusKube. Submitting starts a
 * Delivery check run; the Task becomes done only once that run verifies every pull request merged.
 */
export default function CloseTaskAsDeliveredDialog({ task, open, onOpenChange }: Props) {
  const [urlsText, setUrlsText] = useState("");
  const [note, setNote] = useState("");
  const closeAsDelivered = useCloseTaskAsDelivered();

  const pullRequestUrls = parsePullRequestUrls(urlsText);
  const canSubmit = pullRequestUrls.length > 0 && note.trim().length > 0 && !closeAsDelivered.isPending;

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) closeAsDelivered.reset();
  }

  function handleSubmit() {
    if (!canSubmit) return;
    closeAsDelivered.mutate(
      { id: task.id, body: { pullRequestUrls, note: note.trim() } },
      {
        onSuccess: () => {
          setUrlsText("");
          setNote("");
          handleOpenChange(false);
        },
      }
    );
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md" data-testid="task-close-delivered-dialog">
        <DialogHeader>
          <DialogTitle>Close as delivered</DialogTitle>
          <DialogDescription>
            For work on &ldquo;{task.title}&rdquo; that landed through pull requests made outside
            ChorusKube. ChorusKube checks that each pull request is merged into its repository&rsquo;s
            default branch, then marks the Task done.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="task-close-delivered-prs" className="text-sm font-medium">
              Pull requests <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="task-close-delivered-prs"
              data-testid="task-close-delivered-prs"
              value={urlsText}
              onChange={(e) => setUrlsText(e.target.value)}
              placeholder="https://github.com/owner/repo/pull/123"
              aria-describedby="task-close-delivered-prs-hint"
              rows={3}
              spellCheck={false}
            />
            <p id="task-close-delivered-prs-hint" className="text-xs text-muted-foreground">
              One URL per line.
            </p>
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="task-close-delivered-note" className="text-sm font-medium">
              Note <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="task-close-delivered-note"
              data-testid="task-close-delivered-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="Where and why this was delivered"
              rows={3}
            />
          </div>
        </div>

        <DialogFooter>
          {closeAsDelivered.isError && (
            <p data-testid="task-close-delivered-error" className="text-sm text-destructive mr-auto">
              {apiErrorMessage(closeAsDelivered.error, "Failed to start the delivery check.")}
            </p>
          )}
          <Button variant="ghost" onClick={() => handleOpenChange(false)}>
            Cancel
          </Button>
          <Button data-testid="task-close-delivered-submit" onClick={handleSubmit} disabled={!canSubmit}>
            {closeAsDelivered.isPending ? "Starting check..." : "Start delivery check"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
