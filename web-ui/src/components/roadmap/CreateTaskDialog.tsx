import { useState } from "react";
import { useCreateTask } from "@/hooks/useTasks";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
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
  storyId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Create a Task under a Story. A Task is the only startable unit of work. */
export default function CreateTaskDialog({ storyId, open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");

  const createTask = useCreateTask(storyId);

  function handleCreate() {
    if (!title.trim() || !description.trim()) return;
    createTask.mutate(
      { title: title.trim(), description: description.trim() },
      {
        onSuccess: () => {
          onOpenChange(false);
          resetForm();
        },
      }
    );
  }

  function resetForm() {
    setTitle("");
    setDescription("");
    createTask.reset();
  }

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) resetForm();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>New Task</DialogTitle>
          <DialogDescription>
            Add a Task under this Story. Once created, it can be started as a
            workflow run scoped to the Epic's software project.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="task-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="task-title"
              data-testid="create-task-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Short task name"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="task-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="task-desc"
              data-testid="create-task-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Detailed description of this task..."
              rows={4}
            />
          </div>
        </div>

        <DialogFooter>
          {createTask.isError && (
            <p className="text-sm text-destructive mr-auto">
              Failed to create task.
            </p>
          )}
          <Button
            data-testid="create-task-submit"
            onClick={handleCreate}
            disabled={!title.trim() || !description.trim() || createTask.isPending}
          >
            {createTask.isPending ? "Creating..." : "Create"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
