import { useState } from "react";
import { useCreateFeatureProposal } from "@/hooks/useFeatureProposals";
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
 * Create-proposal dialog. The user picks a single SoftwareProject (a git repo
 * or a user-created repo group) — auto-groups are filtered out by
 * {@link SoftwareProjectSelect}. Multi-repo proposals are no longer expressed
 * as a list of repo ids; if the user wants two repos, they create (or pick) a
 * RepoGroup that bundles them.
 */
export default function CreateProposalDialog({ open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [motivation, setMotivation] = useState("");
  const [softwareProjectId, setSoftwareProjectId] = useState<string>("");

  const createProposal = useCreateFeatureProposal();

  function handleCreate() {
    if (!title.trim() || !description.trim() || !softwareProjectId) return;
    createProposal.mutate(
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
    createProposal.reset();
  }

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) resetForm();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>New Feature Proposal</DialogTitle>
          <DialogDescription>
            Create a feature proposal. Select a software project to develop
            against — when started, the proposal triggers a workflow run scoped
            to that project.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="proposal-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="proposal-title"
              data-testid="create-proposal-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Short feature name"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="proposal-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="proposal-desc"
              data-testid="create-proposal-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Detailed description of what to build..."
              rows={4}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="proposal-motivation" className="text-sm font-medium">
              Motivation
            </label>
            <Textarea
              id="proposal-motivation"
              data-testid="create-proposal-motivation"
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
              testId="create-proposal-software-project-select"
            />
          </div>
        </div>

        <DialogFooter>
          {createProposal.isError && (
            <p className="text-sm text-destructive mr-auto">
              Failed to create proposal.
            </p>
          )}
          <Button
            data-testid="create-proposal-submit"
            onClick={handleCreate}
            disabled={
              !title.trim() ||
              !description.trim() ||
              !softwareProjectId ||
              createProposal.isPending
            }
          >
            {createProposal.isPending ? "Creating..." : "Create"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
