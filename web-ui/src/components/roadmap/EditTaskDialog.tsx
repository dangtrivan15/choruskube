import { useEffect, useState } from "react";
import { useUpdateTask } from "@/hooks/useTasks";
import { ApiError } from "@/lib/api";
import type { TaskResponse } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Props {
  task: TaskResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Edit a backlog Task's title/description — the Task-level clone of `EditEpicDialog`/
 * `EditStoryDialog`. No priority control: the backend update path never persists a Task's
 * `priority` (fixed at create time), so a control here would silently do nothing.
 */
export default function EditTaskDialog({ task, open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");

  const updateTask = useUpdateTask();

  useEffect(() => {
    if (task) {
      setTitle(task.title);
      setDescription(task.description);
    }
  }, [task]);

  function handleSave() {
    if (!task || !title.trim() || !description.trim()) return;
    updateTask.mutate(
      { id: task.id, body: { title: title.trim(), description: description.trim() } },
      { onSuccess: () => onOpenChange(false) }
    );
  }

  // The backend's guard rejection (409) is the only actionable failure text here — everything
  // else (network error, validation) gets a generic fallback. `error.message` is always the
  // generic "API error {status}" wrapper, never the backend's actual explanation.
  const errorText =
    updateTask.error instanceof ApiError &&
    updateTask.error.status === 409 &&
    typeof updateTask.error.body === "string"
      ? updateTask.error.body
      : updateTask.isError
        ? "Failed to update Task."
        : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(isOpen) => {
        onOpenChange(isOpen);
        if (!isOpen) updateTask.reset();
      }}
    >
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Task</DialogTitle>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="edit-task-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="edit-task-title"
              data-testid="edit-task-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-task-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="edit-task-desc"
              data-testid="edit-task-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
            />
          </div>
        </div>

        <DialogFooter>
          {errorText && (
            <p data-testid="edit-task-error" className="text-sm text-destructive mr-auto">
              {errorText}
            </p>
          )}
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            data-testid="edit-task-save"
            onClick={handleSave}
            disabled={!title.trim() || !description.trim() || updateTask.isPending}
          >
            {updateTask.isPending ? "Saving..." : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
