import { useState } from "react";
import { useUpdateRepoGroup, useUpdateRepoGroupTestScope } from "@/hooks/useRepoGroups";
import { apiErrorMessage } from "@/lib/api";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import RepoGroupForm from "@/components/repo-groups/RepoGroupForm";
import RepoGroupTestScopeSection from "@/components/repo-groups/RepoGroupTestScopeSection";
import type { RepoGroup, RepoGroupRequest, RepoGroupTestScopeRequest } from "@/lib/types";

/** Order-insensitive form of a scope so equal edge sets compare equal. */
function canonicalScope(scope: RepoGroupTestScopeRequest, memberIds?: string[]): string {
  const deps = scope.dependencies
    .filter((d) => !memberIds || memberIds.includes(d.repoId))
    .map((d) => ({
      repoId: d.repoId,
      dependsOnRepoIds: d.dependsOnRepoIds
        .filter((id) => !memberIds || memberIds.includes(id))
        .sort(),
    }))
    .filter((d) => d.dependsOnRepoIds.length > 0)
    .sort((a, b) => a.repoId.localeCompare(b.repoId));
  return JSON.stringify({ affectedOnly: scope.affectedOnly, dependencies: deps });
}

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
  const scopeMut = useUpdateRepoGroupTestScope();
  const [scopeError, setScopeError] = useState<string | undefined>();

  function handleOpenChange(next: boolean) {
    onOpenChange(next);
    if (!next) {
      updateMut.reset();
      scopeMut.reset();
      setScopeError(undefined);
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Repo Group</DialogTitle>
          <DialogDescription>
            Update the group's name, agent image, description, member set, or test scope.
          </DialogDescription>
        </DialogHeader>

        {group && (
          <EditRepoGroupBody
            key={group.id}
            group={group}
            availableRepos={availableRepos}
            updateMut={updateMut}
            scopeMut={scopeMut}
            scopeError={scopeError}
            setScopeError={setScopeError}
            onDone={() => onOpenChange(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

interface BodyProps {
  group: RepoGroup;
  availableRepos: { id: string; name: string }[];
  updateMut: ReturnType<typeof useUpdateRepoGroup>;
  scopeMut: ReturnType<typeof useUpdateRepoGroupTestScope>;
  scopeError: string | undefined;
  setScopeError: (message: string | undefined) => void;
  onDone: () => void;
}

/** Remounted per group (keyed by id) so the scope state re-seeds from the new group. */
function EditRepoGroupBody({
  group,
  availableRepos,
  updateMut,
  scopeMut,
  scopeError,
  setScopeError,
  onDone,
}: BodyProps) {
  const initialScope: RepoGroupTestScopeRequest = {
    affectedOnly: group.testAffectedOnly,
    dependencies: group.dependencies,
  };
  const [scope, setScope] = useState<RepoGroupTestScopeRequest>(initialScope);
  const members = group.members.slice().sort((a, b) => a.position - b.position);

  function handleSubmit(body: RepoGroupRequest) {
    // Edges naming a member removed in this same save would be rejected as non-members.
    const nextScope: RepoGroupTestScopeRequest = {
      affectedOnly: scope.affectedOnly,
      dependencies: JSON.parse(canonicalScope(scope, body.memberRepoIds)).dependencies,
    };
    const scopeChanged =
      canonicalScope(nextScope) !== canonicalScope(initialScope);
    setScopeError(undefined);
    updateMut.mutate(
      { id: group.id, body },
      {
        onSuccess: () => {
          if (!scopeChanged) {
            onDone();
            return;
          }
          scopeMut.mutate(
            { id: group.id, body: nextScope },
            {
              onSuccess: onDone,
              onError: (err) =>
                setScopeError(apiErrorMessage(err, "Failed to update test scope.")),
            },
          );
        },
      },
    );
  }

  return (
    <RepoGroupForm
      initial={{
        name: group.name,
        agentImage: group.agentImage ?? "",
        enableDocker: group.enableDocker ?? false,
        dindImage: group.dindImage ?? "",
        agentMemoryRequest: group.agentMemoryRequest ?? "",
        dindMemoryRequest: group.dindMemoryRequest ?? "",
        description: group.description ?? "",
        memberRepoIds: members.map((m) => m.gitRepoId),
      }}
      availableRepos={availableRepos}
      onSubmit={handleSubmit}
      submitLabel="Save"
      submitting={updateMut.isPending || scopeMut.isPending}
      error={updateMut.isError ? "Failed to update repo group." : null}
    >
      <RepoGroupTestScopeSection
        members={members}
        value={scope}
        onChange={setScope}
        error={scopeError}
      />
    </RepoGroupForm>
  );
}
