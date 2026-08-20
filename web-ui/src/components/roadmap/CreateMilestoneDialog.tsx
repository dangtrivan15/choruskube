import { useState } from "react";
import { useCreateMilestone } from "@/hooks/useMilestones";
import SoftwareProjectSelect from "@/components/software-projects/SoftwareProjectSelect";
import TargetDateField from "@/components/roadmap/TargetDateField";
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
 * Create-Milestone dialog (Decision 1/3 of the "Group Epics under a named Milestone / Release"
 * feature, plus the rollup progress / at-risk feature) — a release label scoped to a single
 * software project, named uniquely within it, with an optional target date that later drives the
 * at-risk verdict server-side.
 */
export default function CreateMilestoneDialog({ open, onOpenChange }: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [softwareProjectId, setSoftwareProjectId] = useState<string>("");
  const [targetDate, setTargetDate] = useState<string | null>(null);

  const createMilestone = useCreateMilestone();

  function handleCreate() {
    if (!name.trim() || !softwareProjectId) return;
    createMilestone.mutate(
      {
        name: name.trim(),
        description: description.trim() || null,
        softwareProjectId,
        targetDate,
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
    setName("");
    setDescription("");
    setSoftwareProjectId("");
    setTargetDate(null);
    createMilestone.reset();
  }

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) resetForm();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>New Milestone</DialogTitle>
          <DialogDescription>
            Create a Milestone to group related Epics as one release. Names must be unique within
            the selected software project.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="milestone-name" className="text-sm font-medium">
              Name <span className="text-destructive">*</span>
            </label>
            <Input
              id="milestone-name"
              data-testid="create-milestone-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. Q3 Launch"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="milestone-description" className="text-sm font-medium">
              Description
            </label>
            <Textarea
              id="milestone-description"
              data-testid="create-milestone-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="What this release includes..."
              rows={3}
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">
              Software Project <span className="text-destructive">*</span>
            </label>
            <SoftwareProjectSelect
              value={softwareProjectId}
              onChange={setSoftwareProjectId}
              testId="create-milestone-software-project-select"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label className="text-sm font-medium">Target Date</label>
            <TargetDateField
              value={targetDate}
              onChange={setTargetDate}
              testId="create-milestone-target-date"
            />
          </div>
        </div>

        <DialogFooter>
          {createMilestone.isError && (
            <p className="text-sm text-destructive mr-auto">Failed to create milestone.</p>
          )}
          <Button
            data-testid="create-milestone-submit"
            onClick={handleCreate}
            disabled={!name.trim() || !softwareProjectId || createMilestone.isPending}
          >
            {createMilestone.isPending ? "Creating..." : "Create"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
