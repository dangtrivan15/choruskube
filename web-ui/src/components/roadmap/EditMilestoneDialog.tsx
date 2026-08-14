import { useEffect, useState } from "react";
import { useUpdateMilestone } from "@/hooks/useMilestones";
import type { MilestoneResponse } from "@/lib/types";
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
  milestone: MilestoneResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Rename/edit a Milestone. Unlike `EditEpicDialog`'s SoftwareProject re-pointing, a Milestone's
 * project is fixed at create time (`MilestoneUpdateRequest` carries no `softwareProjectId` —
 * see `MilestoneRequest` vs `MilestoneUpdateRequest` in `types.ts`).
 */
export default function EditMilestoneDialog({ milestone, open, onOpenChange }: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  const updateMilestone = useUpdateMilestone();

  useEffect(() => {
    if (milestone) {
      setName(milestone.name);
      setDescription(milestone.description ?? "");
    }
  }, [milestone]);

  function handleSave() {
    if (!milestone || !name.trim()) return;
    updateMilestone.mutate(
      {
        id: milestone.id,
        body: {
          name: name.trim(),
          description: description.trim() || null,
          targetDate: milestone.targetDate,
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
        if (!isOpen) updateMilestone.reset();
      }}
    >
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Milestone</DialogTitle>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="edit-milestone-name" className="text-sm font-medium">
              Name <span className="text-destructive">*</span>
            </label>
            <Input
              id="edit-milestone-name"
              data-testid="edit-milestone-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-milestone-description" className="text-sm font-medium">
              Description
            </label>
            <Textarea
              id="edit-milestone-description"
              data-testid="edit-milestone-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={3}
            />
          </div>
        </div>

        <DialogFooter>
          {updateMilestone.isError && (
            <p className="text-sm text-destructive mr-auto">Failed to update milestone.</p>
          )}
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            data-testid="edit-milestone-save"
            onClick={handleSave}
            disabled={!name.trim() || updateMilestone.isPending}
          >
            {updateMilestone.isPending ? "Saving..." : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
