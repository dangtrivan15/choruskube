import { useState } from "react";
import { useGitRepos, useDeleteGitRepo } from "@/hooks/useGitRepos";
import { formatDistanceToNow } from "date-fns";
import { Pencil, Trash2, Plus } from "lucide-react";
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
import { Badge } from "@/components/ui/badge";
import TruncatedText from "@/components/ui/TruncatedText";
import Pagination from "@/components/ui/Pagination";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import type { GitRepoResponse, PaginationParams } from "@/lib/types";
import Authorized from "@/components/Authorized";
import CreateGitRepoDialog from "@/components/git-repos/CreateGitRepoDialog";
import EditGitRepoDialog from "@/components/git-repos/EditGitRepoDialog";
import { repoDisplayName } from "@/lib/utils";

/**
 * Repositories tab body for the Software Projects page. Extracted from the
 * former GitRepoListPage so that page-level chrome (PageShell + PageHeader)
 * can live one level up in SoftwareProjectsPage. The "New Repo" CTA stays
 * inside this tab body — the page header above it is "Software Projects",
 * so a per-tab create button keeps the action close to its target list.
 */
export default function RepositoriesTab() {
  const [page, setPage] = useState(0);
  const pagination: PaginationParams = { page, size: 20 };
  const { data: pageData, isLoading } = useGitRepos(pagination);
  const repos = pageData?.content;

  const deleteGitRepo = useDeleteGitRepo();
  const [editingRepo, setEditingRepo] = useState<GitRepoResponse | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [deletingRepo, setDeletingRepo] = useState<GitRepoResponse | null>(null);

  function handleDelete() {
    if (!deletingRepo) return;
    deleteGitRepo.mutate(deletingRepo.id, {
      onSuccess: () => setDeletingRepo(null),
    });
  }

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <Authorized require="canOperate">
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="size-4" />
            New Repo
          </Button>
        </Authorized>
      </div>

      <Table className="table-fixed">
        <TableHeader>
          <TableRow>
            <TableHead className="w-56">Repository</TableHead>
            <TableHead className="w-32">Branch</TableHead>
            <TableHead className="hidden w-48 md:table-cell">Test Command</TableHead>
            <TableHead className="hidden w-24 md:table-cell">Docker</TableHead>
            <TableHead className="hidden w-32 md:table-cell">Created</TableHead>
            <TableHead className="w-24" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading &&
            Array.from({ length: 3 }).map((_, i) => (
              <TableRow key={i}>
                <TableCell><Skeleton className="h-4 w-48" /></TableCell>
                <TableCell><Skeleton className="h-4 w-16" /></TableCell>
                <TableCell className="hidden md:table-cell"><Skeleton className="h-4 w-32" /></TableCell>
                <TableCell className="hidden md:table-cell"><Skeleton className="h-4 w-12" /></TableCell>
                <TableCell className="hidden md:table-cell"><Skeleton className="h-4 w-20" /></TableCell>
                <TableCell />
              </TableRow>
            ))}
          {repos?.map((r) => (
            <TableRow key={r.id}>
              <TableCell className="font-medium font-mono text-sm">
                <div className="min-w-0">
                  <TruncatedText>{repoDisplayName(r.url)}</TruncatedText>
                </div>
              </TableCell>
              <TableCell className="text-muted-foreground">{r.defaultBranch}</TableCell>
              <TableCell className="hidden text-muted-foreground font-mono text-sm md:table-cell">
                <TruncatedText>{r.testCommand ?? "—"}</TruncatedText>
              </TableCell>
              <TableCell className="hidden md:table-cell">
                {r.enableDocker ? (
                  <Badge variant="outline" className="border-status-info/40 text-status-info">On</Badge>
                ) : (
                  <Badge variant="secondary">Off</Badge>
                )}
              </TableCell>
              <TableCell className="hidden text-muted-foreground md:table-cell">
                {formatDistanceToNow(new Date(r.createdAt), { addSuffix: true })}
              </TableCell>
              <TableCell>
                <Authorized require="canAdmin">
                  <div className="flex gap-1">
                    <Button data-testid="repo-edit-button" variant="ghost" size="icon" onClick={() => setEditingRepo(r)}>
                      <Pencil className="size-4" />
                    </Button>
                    <Button data-testid="repo-delete-button" variant="ghost" size="icon" onClick={() => setDeletingRepo(r)}>
                      <Trash2 className="size-4" />
                    </Button>
                  </div>
                </Authorized>
              </TableCell>
            </TableRow>
          ))}
          {repos && repos.length === 0 && (
            <TableRow>
              <TableCell colSpan={100} className="text-center text-muted-foreground py-8">
                No git repos registered yet. Click "New Repo" to add one.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      {pageData && (
        <Pagination
          page={pageData.number}
          totalPages={pageData.totalPages}
          onPageChange={setPage}
        />
      )}

      <EditGitRepoDialog
        gitRepo={editingRepo}
        open={editingRepo !== null}
        onOpenChange={(open) => { if (!open) setEditingRepo(null); }}
      />

      <CreateGitRepoDialog open={createOpen} onOpenChange={setCreateOpen} />

      <Dialog
        open={deletingRepo !== null}
        onOpenChange={(open) => { if (!open) { setDeletingRepo(null); deleteGitRepo.reset(); } }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Git Repo</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete "{deletingRepo ? repoDisplayName(deletingRepo.url) : ""}"?
              Templates using this repo will lose their configuration.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {deleteGitRepo.isError && (
              <p className="text-sm text-destructive mr-auto">
                Failed to delete git repo.
              </p>
            )}
            <Button
              variant="ghost"
              onClick={() => { setDeletingRepo(null); deleteGitRepo.reset(); }}
            >
              Cancel
            </Button>
            <Button
              variant="destructive"
              onClick={handleDelete}
              disabled={deleteGitRepo.isPending}
            >
              {deleteGitRepo.isPending ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
