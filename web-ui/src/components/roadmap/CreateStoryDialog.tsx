import { useState } from "react";
import { useCreateStory } from "@/hooks/useStories";
import PrioritySelect from "@/components/roadmap/PrioritySelect";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { Priority } from "@/lib/types";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Props {
  epicId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Create a Story under an Epic. Stories inherit the Epic's software project. */
export default function CreateStoryDialog({ epicId, open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  // Defaults to "medium", matching the backend's omitted-priority fallback.
  const [priority, setPriority] = useState<Priority>("medium");

  const createStory = useCreateStory(epicId);

  function handleCreate() {
    if (!title.trim() || !description.trim()) return;
    createStory.mutate(
      { title: title.trim(), description: description.trim(), priority },
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
    setPriority("medium");
    createStory.reset();
  }

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) resetForm();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>New Story</DialogTitle>
          <DialogDescription>
            Break this Epic into a Story. Add Tasks under it once created —
            only a Task can be started as a workflow run.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="story-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="story-title"
              data-testid="create-story-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Short story name"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="story-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="story-desc"
              data-testid="create-story-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Detailed description of this story..."
              rows={4}
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">Priority</label>
            <PrioritySelect
              value={priority}
              onChange={setPriority}
              testId="create-story-priority-select"
            />
          </div>
        </div>

        <DialogFooter>
          {createStory.isError && (
            <p className="text-sm text-destructive mr-auto">
              Failed to create story.
            </p>
          )}
          <Button
            data-testid="create-story-submit"
            onClick={handleCreate}
            disabled={!title.trim() || !description.trim() || createStory.isPending}
          >
            {createStory.isPending ? "Creating..." : "Create"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
