import { useUpdateRepoGroup } from "@/hooks/useRepoGroups";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import RepoGroupForm from "@/components/repo-groups/RepoGroupForm";
import type { RepoGroup, RepoGroupRequest } from "@/lib/types";

interface Props {
  group: RepoGroup | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  availableRepos: { id: string; name: string }[];
}

/**
 * Edit a RepoGroup via PUT /api/v1/repo-groups/{id}. Wraps {@link RepoGroupForm}
 * with `initial` seeded from the target group and routes submit through
 * {@link useUpdateRepoGroup}. The form is keyed by group.id so reopening on a
 * different row remounts it with fresh state — RepoGroupForm initializes from
 * {@code initial} on mount only.
 *
 * Sends the full body (name + image + desc + memberRepoIds) on every save.
 * Backend's replaceMembers does DELETE→flush→INSERT to avoid composite-PK
 * collisions, so the round-trip is idempotent even when the member list is
 * unchanged. Group sizes are small in practice (typically <10 repos), so the
 * extra writes are negligible.
 */
export default function EditRepoGroupDialog({
  group,
  open,
  onOpenChange,
  availableRepos,
}: Props) {
  const updateMut = useUpdateRepoGroup();

  function handleSubmit(body: RepoGroupRequest) {
    if (!group) return;
    updateMut.mutate(
      { id: group.id, body },
      { onSuccess: () => onOpenChange(false) },
    );
  }

  function handleOpenChange(next: boolean) {
    onOpenChange(next);
    if (!next) updateMut.reset();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Repo Group</DialogTitle>
          <DialogDescription>
            Update the group's name, agent image, description, or member set.
          </DialogDescription>
        </DialogHeader>

        {group && (
          <RepoGroupForm
            key={group.id}
            initial={{
              name: group.name,
              agentImage: group.agentImage ?? "",
              description: group.description ?? "",
              memberRepoIds: group.members
                .slice()
                .sort((a, b) => a.position - b.position)
                .map((m) => m.gitRepoId),
            }}
            availableRepos={availableRepos}
            onSubmit={handleSubmit}
            submitLabel="Save"
            submitting={updateMut.isPending}
            error={updateMut.isError ? "Failed to update repo group." : null}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}
