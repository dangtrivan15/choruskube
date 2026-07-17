import { useState } from "react";
import { useCreateEpic } from "@/hooks/useEpics";
import SoftwareProjectSelect from "@/components/software-projects/SoftwareProjectSelect";
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
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Create-Epic dialog. The user picks a single SoftwareProject (a git repo
 * or a user-created repo group) — auto-groups are filtered out by
 * {@link SoftwareProjectSelect}. Multi-repo Epics are not expressed as a
 * list of repo ids; if the user wants two repos, they create (or pick) a
 * RepoGroup that bundles them (Decision 4).
 */
export default function CreateEpicDialog({ open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [motivation, setMotivation] = useState("");
  const [softwareProjectId, setSoftwareProjectId] = useState<string>("");

  const createEpic = useCreateEpic();

  function handleCreate() {
    if (!title.trim() || !description.trim() || !softwareProjectId) return;
    createEpic.mutate(
      {
        title: title.trim(),
        description: description.trim(),
        motivation: motivation.trim() || null,
        softwareProjectId,
      },
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
    setMotivation("");
    setSoftwareProjectId("");
    createEpic.reset();
  }

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) resetForm();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>New Epic</DialogTitle>
          <DialogDescription>
            Create an Epic. Select a software project to develop against —
            its Stories and Tasks inherit this target, and starting a Task
            triggers a workflow run scoped to it.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="epic-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="epic-title"
              data-testid="create-epic-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Short feature name"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="epic-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="epic-desc"
              data-testid="create-epic-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Detailed description of what to build..."
              rows={4}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="epic-motivation" className="text-sm font-medium">
              Motivation
            </label>
            <Textarea
              id="epic-motivation"
              data-testid="create-epic-motivation"
              value={motivation}
              onChange={(e) => setMotivation(e.target.value)}
              placeholder="Why this feature matters..."
              rows={2}
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">
              Software Project <span className="text-destructive">*</span>
            </label>

            <SoftwareProjectSelect
              value={softwareProjectId}
              onChange={setSoftwareProjectId}
              testId="create-epic-software-project-select"
            />
          </div>
        </div>

        <DialogFooter>
          {createEpic.isError && (
            <p className="text-sm text-destructive mr-auto">
              Failed to create epic.
            </p>
          )}
          <Button
            data-testid="create-epic-submit"
            onClick={handleCreate}
            disabled={
              !title.trim() ||
              !description.trim() ||
              !softwareProjectId ||
              createEpic.isPending
            }
          >
            {createEpic.isPending ? "Creating..." : "Create"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
