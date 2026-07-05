import { useEffect, useState } from "react";
import { useUpdateFeatureProposal } from "@/hooks/useFeatureProposals";
import type { FeatureProposalResponse } from "@/lib/types";
import SoftwareProjectSelect from "@/components/software-projects/SoftwareProjectSelect";
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
  proposal: FeatureProposalResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Edit a backlog proposal. The {@code SoftwareProject} can be re-pointed (e.g.
 * to a different repo or repo group) but the workflow run, once launched, is
 * already pinned to the prior selection — so editing only affects future
 * starts.
 */
export default function EditProposalDialog({ proposal, open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [motivation, setMotivation] = useState("");
  const [softwareProjectId, setSoftwareProjectId] = useState<string>("");

  const updateProposal = useUpdateFeatureProposal();

  useEffect(() => {
    if (proposal) {
      setTitle(proposal.title);
      setDescription(proposal.description);
      setMotivation(proposal.motivation ?? "");
      setSoftwareProjectId(proposal.softwareProject.id);
    }
  }, [proposal]);

  function handleSave() {
    if (!proposal || !title.trim() || !description.trim() || !softwareProjectId) return;
    updateProposal.mutate(
      {
        id: proposal.id,
        body: {
          title: title.trim(),
          description: description.trim(),
          motivation: motivation.trim() || null,
          softwareProjectId,
        },
      },
      {
        onSuccess: () => onOpenChange(false),
      }
    );
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(isOpen) => {
        onOpenChange(isOpen);
        if (!isOpen) updateProposal.reset();
      }}
    >
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Proposal</DialogTitle>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="edit-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="edit-title"
              data-testid="edit-proposal-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="edit-desc"
              data-testid="edit-proposal-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-motivation" className="text-sm font-medium">
              Motivation
            </label>
            <Textarea
              id="edit-motivation"
              value={motivation}
              onChange={(e) => setMotivation(e.target.value)}
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
              testId="edit-proposal-software-project-select"
            />
          </div>
        </div>

        <DialogFooter>
          {updateProposal.isError && (
            <p className="text-sm text-destructive mr-auto">
              Failed to update proposal.
            </p>
          )}
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            data-testid="edit-proposal-save"
            onClick={handleSave}
            disabled={
              !title.trim() ||
              !description.trim() ||
              !softwareProjectId ||
              updateProposal.isPending
            }
          >
            {updateProposal.isPending ? "Saving..." : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
