import { useEffect, useState } from "react";
import { useUpdateStory } from "@/hooks/useStories";
import { ApiError } from "@/lib/api";
import type { StoryResponse } from "@/lib/types";
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
  story: StoryResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Edit a backlog Story's title/description — the Story-level clone of `EditEpicDialog`, scoped to
 * the two fields the backend `StoryUpdateRequest` actually carries (priority moves via its own
 * inline selector, not this dialog).
 */
export default function EditStoryDialog({ story, open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");

  const updateStory = useUpdateStory();

  useEffect(() => {
    if (story) {
      setTitle(story.title);
      setDescription(story.description);
    }
  }, [story]);

  function handleSave() {
    if (!story || !title.trim() || !description.trim()) return;
    updateStory.mutate(
      { id: story.id, body: { title: title.trim(), description: description.trim() } },
      { onSuccess: () => onOpenChange(false) }
    );
  }

  // The backend's guard rejection (409) is the only actionable failure text here — everything
  // else (network error, validation) gets a generic fallback. `error.message` is always the
  // generic "API error {status}" wrapper, never the backend's actual explanation.
  const errorText =
    updateStory.error instanceof ApiError &&
    updateStory.error.status === 409 &&
    typeof updateStory.error.body === "string"
      ? updateStory.error.body
      : updateStory.isError
        ? "Failed to update Story."
        : null;

  return (
    <Dialog
      open={open}
      onOpenChange={(isOpen) => {
        onOpenChange(isOpen);
        if (!isOpen) updateStory.reset();
      }}
    >
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Story</DialogTitle>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="edit-story-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="edit-story-title"
              data-testid="edit-story-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-story-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="edit-story-desc"
              data-testid="edit-story-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
            />
          </div>
        </div>

        <DialogFooter>
          {errorText && (
            <p data-testid="edit-story-error" className="text-sm text-destructive mr-auto">
              {errorText}
            </p>
          )}
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            data-testid="edit-story-save"
            onClick={handleSave}
            disabled={!title.trim() || !description.trim() || updateStory.isPending}
          >
            {updateStory.isPending ? "Saving..." : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
