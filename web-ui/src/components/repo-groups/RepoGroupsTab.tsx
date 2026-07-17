import { Fragment, useState } from "react";
import { formatDistanceToNow } from "date-fns";
import { Trash2, Plus, ChevronDown, ChevronRight, Pencil } from "lucide-react";
import { useGitRepos } from "@/hooks/useGitRepos";
import {
  useRepoGroups,
  useCreateRepoGroup,
  useDeleteRepoGroup,
} from "@/hooks/useRepoGroups";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import TruncatedText from "@/components/ui/TruncatedText";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import Authorized from "@/components/Authorized";
import { repoDisplayName } from "@/lib/utils";
import RepoGroupForm from "@/components/repo-groups/RepoGroupForm";
import EditRepoGroupDialog from "@/components/repo-groups/EditRepoGroupDialog";
import type { RepoGroup, RepoGroupRequest } from "@/lib/types";

/**
 * Repo Groups tab body for the Software Projects page. Lists existing groups,
 * exposes a "+ New Group" CTA that opens a dialog wrapping {@link RepoGroupForm},
 * and supports per-row edit (via {@link EditRepoGroupDialog}) and delete with
 * confirmation dialogs.
 *
 * Data sources:
 *  - {@code useRepoGroups()} — paginated-or-not list of groups in the active org
 *  - {@code useGitRepos({ size: 100 })} — multi-select source for the create form,
 *    pulling a single large page rather than paginating.
 */
export default function RepoGroupsTab() {
  const { data: groups, isLoading } = useRepoGroups();
  const { data: reposPage } = useGitRepos({ size: 100 });
  const repos = reposPage?.content ?? [];
  const repoNameById = new Map(repos.map((r) => [r.id, repoDisplayName(r.url)]));

  const visibleGroups = groups ?? [];

  const createMut = useCreateRepoGroup();
  const deleteMut = useDeleteRepoGroup();

  const [createOpen, setCreateOpen] = useState(false);
  const [editingGroup, setEditingGroup] = useState<RepoGroup | null>(null);
  const [deletingGroup, setDeletingGroup] = useState<RepoGroup | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  function toggleExpanded(id: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  const availableRepos = repos.map((r) => ({
    id: r.id,
    name: repoDisplayName(r.url),
  }));

  function handleCreate(body: RepoGroupRequest) {
    createMut.mutate(body, {
      onSuccess: () => setCreateOpen(false),
    });
  }

  function handleCreateOpenChange(open: boolean) {
    setCreateOpen(open);
    if (!open) createMut.reset();
  }

  function handleDelete() {
    if (!deletingGroup) return;
    deleteMut.mutate(deletingGroup.id, {
      onSuccess: () => setDeletingGroup(null),
    });
  }

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Authorized require="canOperate">
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="size-4" />
            New Group
          </Button>
        </Authorized>
      </div>

      <Table className="table-fixed">
        <TableHeader>
          <TableRow>
            <TableHead className="w-64">Name</TableHead>
            <TableHead className="w-32">Members</TableHead>
            <TableHead className="hidden w-48 md:table-cell">Agent Image</TableHead>
            <TableHead className="hidden w-40 md:table-cell">Created</TableHead>
            <TableHead className="w-24" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading &&
            Array.from({ length: 3 }).map((_, i) => (
              <TableRow key={i}>
                <TableCell><Skeleton className="h-4 w-32" /></TableCell>
                <TableCell><Skeleton className="h-4 w-16" /></TableCell>
                <TableCell className="hidden md:table-cell"><Skeleton className="h-4 w-48" /></TableCell>
                <TableCell className="hidden md:table-cell"><Skeleton className="h-4 w-24" /></TableCell>
                <TableCell />
              </TableRow>
            ))}
          {visibleGroups.map((g) => {
            const isExpanded = expandedIds.has(g.id);
            const memberCount = g.members.length;
            return (
              <Fragment key={g.id}>
                <TableRow>
                  <TableCell className="font-medium">{g.name}</TableCell>
                  <TableCell>
                    <button
                      type="button"
                      onClick={() => toggleExpanded(g.id)}
                      aria-expanded={isExpanded}
                      aria-label={`${isExpanded ? "Collapse" : "Expand"} ${g.name} members`}
                      className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
                    >
                      {isExpanded ? (
                        <ChevronDown className="size-4" />
                      ) : (
                        <ChevronRight className="size-4" />
                      )}
                      {memberCount} {memberCount === 1 ? "repo" : "repos"}
                    </button>
                  </TableCell>
                  <TableCell className="hidden text-muted-foreground font-mono text-sm md:table-cell">
                    <TruncatedText>{g.agentImage ?? "—"}</TruncatedText>
                  </TableCell>
                  <TableCell className="hidden text-muted-foreground md:table-cell">
                    {formatDistanceToNow(new Date(g.createdAt), { addSuffix: true })}
                  </TableCell>
                  <TableCell>
                    <Authorized require="canAdmin">
                      <div className="flex gap-1">
                        <Button
                          data-testid="repo-group-edit-button"
                          variant="ghost"
                          size="icon"
                          onClick={() => setEditingGroup(g)}
                        >
                          <Pencil className="size-4" />
                        </Button>
                        <Button
                          data-testid="repo-group-delete-button"
                          variant="ghost"
                          size="icon"
                          onClick={() => setDeletingGroup(g)}
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      </div>
                    </Authorized>
                  </TableCell>
                </TableRow>
                {isExpanded && (
                  <TableRow className="hover:bg-transparent">
                    <TableCell
                      colSpan={5}
                      className="bg-muted/30 py-3"
                    >
                      <ul className="ml-12 space-y-1 font-mono text-sm text-muted-foreground">
                        {g.members.map((m) => (
                          <li key={m.gitRepoId}>
                            {repoNameById.get(m.gitRepoId) ?? m.name}
                          </li>
                        ))}
                      </ul>
                    </TableCell>
                  </TableRow>
                )}
              </Fragment>
            );
          })}
          {visibleGroups.length === 0 && !isLoading && (
            <TableRow>
              <TableCell colSpan={100} className="text-center text-muted-foreground py-8">
                No repo groups yet. Click "New Group" to create one.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <Dialog open={createOpen} onOpenChange={handleCreateOpenChange}>
        <DialogContent size="md">
          <DialogHeader>
            <DialogTitle>New Repo Group</DialogTitle>
            <DialogDescription>
              Group multiple repositories so they can be selected as a single
              software project for runs and Epics.
            </DialogDescription>
          </DialogHeader>

          <RepoGroupForm
            key={createOpen ? "open" : "closed"}
            availableRepos={availableRepos}
            onSubmit={handleCreate}
            submitLabel="Create"
            submitting={createMut.isPending}
            error={createMut.isError ? "Failed to create repo group." : null}
          />
        </DialogContent>
      </Dialog>

      <EditRepoGroupDialog
        group={editingGroup}
        open={editingGroup !== null}
        onOpenChange={(open) => {
          if (!open) setEditingGroup(null);
        }}
        availableRepos={availableRepos}
      />

      <Dialog
        open={deletingGroup !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDeletingGroup(null);
            deleteMut.reset();
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Repo Group</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete "{deletingGroup?.name}"? This cannot
              be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {deleteMut.isError && (
              <p className="text-sm text-destructive mr-auto">
                Failed to delete repo group.
              </p>
            )}
            <Button
              variant="ghost"
              onClick={() => {
                setDeletingGroup(null);
                deleteMut.reset();
              }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={deleteMut.isPending}
            >
              {deleteMut.isPending ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
