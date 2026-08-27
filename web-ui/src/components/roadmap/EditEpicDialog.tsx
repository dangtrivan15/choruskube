import { useEffect, useState } from "react";
import { useUpdateEpic } from "@/hooks/useEpics";
import { useAssignEpicMilestone } from "@/hooks/useMilestones";
import type { EpicResponse } from "@/lib/types";
import SoftwareProjectSelect from "@/components/software-projects/SoftwareProjectSelect";
import MilestoneSelect from "@/components/roadmap/MilestoneSelect";
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
  epic: EpicResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Edit a backlog Epic. The {@code SoftwareProject} can be re-pointed (e.g.
 * to a different repo or repo group), but Tasks already created under this
 * Epic keep the project they were created with — editing only
 * affects Tasks created after the change.
 */
export default function EditEpicDialog({ epic, open, onOpenChange }: Props) {
  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [motivation, setMotivation] = useState("");
  const [softwareProjectId, setSoftwareProjectId] = useState<string>("");
  const [milestoneId, setMilestoneId] = useState<string | null>(null);

  const updateEpic = useUpdateEpic();
  const assignMilestone = useAssignEpicMilestone();

  function handleSoftwareProjectChange(id: string) {
    setSoftwareProjectId(id);
    // A Milestone must belong to the same project as the Epic — clear a stale
    // selection from the previous project rather than silently retain a now cross-project
    // Milestone tag when the Epic is re-pointed. Mirrors CreateEpicDialog; the backend's PUT
    // additionally un-tags on a project change, so this keeps the picker and the saved state in
    // agreement.
    setMilestoneId(null);
  }

  useEffect(() => {
    if (epic) {
      setTitle(epic.title);
      setDescription(epic.description);
      setMotivation(epic.motivation ?? "");
      setSoftwareProjectId(epic.softwareProject.id);
      setMilestoneId(epic.milestone?.id ?? null);
    }
  }, [epic]);

  function handleSave() {
    if (!epic || !title.trim() || !description.trim() || !softwareProjectId) return;
    updateEpic.mutate(
      {
        id: epic.id,
        body: {
          title: title.trim(),
          description: description.trim(),
          motivation: motivation.trim() || null,
          softwareProjectId,
        },
      },
      {
        onSuccess: () => {
          // Milestone assignment doesn't ride the full PUT (EpicUpdateRequest
          // carries no milestoneId) — apply it as a second mutation only when it actually
          // changed, mirroring CreateEpicDialog's post-create chain.
          const currentMilestoneId = epic.milestone?.id ?? null;
          if (milestoneId !== currentMilestoneId) {
            assignMilestone.mutate(
              { id: epic.id, milestoneId },
              { onSettled: () => onOpenChange(false) }
            );
          } else {
            onOpenChange(false);
          }
        },
      }
    );
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(isOpen) => {
        onOpenChange(isOpen);
        if (!isOpen) updateEpic.reset();
      }}
    >
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Epic</DialogTitle>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="edit-epic-title" className="text-sm font-medium">
              Title <span className="text-destructive">*</span>
            </label>
            <Input
              id="edit-epic-title"
              data-testid="edit-epic-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-epic-desc" className="text-sm font-medium">
              Description <span className="text-destructive">*</span>
            </label>
            <Textarea
              id="edit-epic-desc"
              data-testid="edit-epic-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={4}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-epic-motivation" className="text-sm font-medium">
              Motivation
            </label>
            <Textarea
              id="edit-epic-motivation"
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
              onChange={handleSoftwareProjectChange}
              testId="edit-epic-software-project-select"
            />
          </div>

          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">Milestone</label>
            <MilestoneSelect
              value={milestoneId}
              onChange={setMilestoneId}
              softwareProjectId={softwareProjectId || undefined}
              testId="edit-epic-milestone-select"
            />
          </div>
        </div>

        <DialogFooter>
          {(updateEpic.isError || assignMilestone.isError) && (
            <p className="text-sm text-destructive mr-auto">
              Failed to update epic.
            </p>
          )}
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            data-testid="edit-epic-save"
            onClick={handleSave}
            disabled={
              !title.trim() ||
              !description.trim() ||
              !softwareProjectId ||
              updateEpic.isPending ||
              assignMilestone.isPending
            }
          >
            {updateEpic.isPending || assignMilestone.isPending ? "Saving..." : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
