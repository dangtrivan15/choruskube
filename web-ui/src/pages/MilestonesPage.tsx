import { Fragment, useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { ArrowLeft, ChevronDown, ChevronRight, Trash2, Plus, Pencil } from "lucide-react";
import Authorized from "@/components/Authorized";
import PageHeader from "@/components/layout/PageHeader";
import PageShell from "@/components/layout/PageShell";
import { useMilestones, useDeleteMilestone, useMilestoneAtRiskItems } from "@/hooks/useMilestones";
import { useSoftwareProjects } from "@/hooks/useSoftwareProjects";
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
import CreateMilestoneDialog from "@/components/roadmap/CreateMilestoneDialog";
import EditMilestoneDialog from "@/components/roadmap/EditMilestoneDialog";
import MilestoneProgressBar from "@/components/roadmap/MilestoneProgressBar";
import AtRiskBadge from "@/components/roadmap/AtRiskBadge";
import type { MilestoneResponse } from "@/lib/types";

/** Total column count of the Milestone table — the `colSpan` the at-risk drill-down row spans. */
const COLUMN_COUNT = 7;

/**
 * At-risk drill-down row for one Milestone, rendered only while that row is expanded. Fetches
 * `GET /milestones/{id}/at-risk-items` lazily via `useMilestoneAtRiskItems(milestoneId, true)` —
 * nothing is fetched for rows that are never expanded.
 */
function AtRiskDrillDownRow({ milestoneId }: { milestoneId: string }) {
  const { data, isLoading } = useMilestoneAtRiskItems(milestoneId, true);
  const items = data?.items ?? [];

  return (
    <TableRow data-testid="milestone-at-risk-drilldown">
      <TableCell colSpan={COLUMN_COUNT} className="bg-muted/30">
        {isLoading && <p className="text-xs text-muted-foreground">Loading at-risk items...</p>}
        {!isLoading && items.length === 0 && (
          <p className="text-xs text-muted-foreground" data-testid="milestone-at-risk-empty">
            No at-risk items.
          </p>
        )}
        {!isLoading && items.length > 0 && (
          <ul className="flex flex-col gap-1">
            {items.map((item) => (
              <li
                key={item.id}
                data-testid="milestone-at-risk-item"
                className="flex items-center gap-3 text-xs"
              >
                <span className="rounded bg-muted px-1.5 py-0.5 font-medium uppercase text-muted-foreground">
                  {item.tier}
                </span>
                <TruncatedText className="flex-1">{item.title}</TruncatedText>
                <span className="text-muted-foreground">{item.targetDate}</span>
                <span className="text-muted-foreground">{item.status}</span>
              </li>
            ))}
          </ul>
        )}
      </TableCell>
    </TableRow>
  );
}

/**
 * Milestone management surface (list/create/edit/delete) — the org-wide equivalent of
 * `RepoGroupsTab` for Milestones (Decision 1). Unlike `RepoGroupsTab`, this is a standalone
 * routed page (`/roadmap/milestones`), reached from `RoadmapPage`'s toolbar, not a sidebar
 * entry — mirroring how Board/Timeline are in-page links rather than their own nav item.
 */
export default function MilestonesPage() {
  const { data: milestonesPage, isLoading } = useMilestones();
  const milestones = milestonesPage?.content ?? [];
  const { data: projects } = useSoftwareProjects();
  const projectNameById = new Map((projects ?? []).map((p) => [p.id, p.name]));

  const deleteMut = useDeleteMilestone();

  const [createOpen, setCreateOpen] = useState(false);
  const [editingMilestone, setEditingMilestone] = useState<MilestoneResponse | null>(null);
  const [deletingMilestone, setDeletingMilestone] = useState<MilestoneResponse | null>(null);
  // At most one Milestone's at-risk drill-down is expanded at a time — mirrors how only one
  // Create/Edit dialog can be open at once.
  const [expandedMilestoneId, setExpandedMilestoneId] = useState<string | null>(null);

  function handleDelete() {
    if (!deletingMilestone) return;
    deleteMut.mutate(deletingMilestone.id, {
      onSuccess: () => setDeletingMilestone(null),
    });
  }

  return (
    <PageShell>
      <Link
        to="/roadmap"
        className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-4" />
        Back to Roadmap
      </Link>

      <PageHeader title="Milestones" data-testid="milestones-heading">
        <Authorized require="canOperate">
          <Button data-testid="new-milestone-button" size="sm" onClick={() => setCreateOpen(true)}>
            <Plus className="size-4" />
            New Milestone
          </Button>
        </Authorized>
      </PageHeader>

      <Table className="table-fixed" data-testid="milestone-list">
        <TableHeader>
          <TableRow>
            <TableHead className="w-56">Name</TableHead>
            <TableHead className="w-48">Software Project</TableHead>
            <TableHead className="w-24">Epics</TableHead>
            <TableHead className="w-40">Progress</TableHead>
            <TableHead className="w-32">At Risk</TableHead>
            <TableHead className="hidden w-40 md:table-cell">Created</TableHead>
            <TableHead className="w-24" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading &&
            Array.from({ length: 3 }).map((_, i) => (
              <TableRow key={i}>
                <TableCell><Skeleton className="h-4 w-32" /></TableCell>
                <TableCell><Skeleton className="h-4 w-24" /></TableCell>
                <TableCell><Skeleton className="h-4 w-10" /></TableCell>
                <TableCell><Skeleton className="h-4 w-24" /></TableCell>
                <TableCell><Skeleton className="h-4 w-16" /></TableCell>
                <TableCell className="hidden md:table-cell"><Skeleton className="h-4 w-24" /></TableCell>
                <TableCell />
              </TableRow>
            ))}
          {milestones.map((m) => {
            const expanded = expandedMilestoneId === m.id;
            const hasAtRiskItems = m.atRiskItemCount > 0;
            return (
              <Fragment key={m.id}>
                <TableRow data-testid="milestone-item">
                  <TableCell className="font-medium">
                    <TruncatedText>{m.name}</TruncatedText>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    <TruncatedText>{projectNameById.get(m.softwareProjectId) ?? "—"}</TruncatedText>
                  </TableCell>
                  <TableCell data-testid="milestone-epic-count" className="text-muted-foreground">
                    {m.epicCount}
                  </TableCell>
                  <TableCell data-testid="milestone-progress-cell">
                    <div className="flex items-center gap-2">
                      <MilestoneProgressBar progress={m.progress} data-testid="milestone-progress-bar" />
                      <span className="whitespace-nowrap text-xs text-muted-foreground">
                        {m.progress.doneTasks}/{m.progress.totalTasks}
                      </span>
                    </div>
                  </TableCell>
                  <TableCell data-testid="milestone-at-risk-cell">
                    {hasAtRiskItems ? (
                      <button
                        type="button"
                        data-testid="milestone-at-risk-toggle"
                        className="flex items-center gap-1"
                        onClick={() => setExpandedMilestoneId(expanded ? null : m.id)}
                      >
                        {expanded ? (
                          <ChevronDown className="size-3 text-muted-foreground" />
                        ) : (
                          <ChevronRight className="size-3 text-muted-foreground" />
                        )}
                        <AtRiskBadge
                          atRisk={m.atRisk}
                          count={m.atRiskItemCount}
                          data-testid="milestone-at-risk-badge"
                        />
                      </button>
                    ) : (
                      <AtRiskBadge
                        atRisk={m.atRisk}
                        count={m.atRiskItemCount}
                        data-testid="milestone-at-risk-badge"
                      />
                    )}
                  </TableCell>
                  <TableCell className="hidden text-muted-foreground md:table-cell">
                    {formatDistanceToNow(new Date(m.createdAt), { addSuffix: true })}
                  </TableCell>
                  <TableCell>
                    <Authorized require="canAdmin">
                      <div className="flex gap-1">
                        <Button
                          data-testid="milestone-edit-button"
                          variant="ghost"
                          size="icon"
                          onClick={() => setEditingMilestone(m)}
                        >
                          <Pencil className="size-4" />
                        </Button>
                        <Button
                          data-testid="milestone-delete-button"
                          variant="ghost"
                          size="icon"
                          onClick={() => setDeletingMilestone(m)}
                        >
                          <Trash2 className="size-4" />
                        </Button>
                      </div>
                    </Authorized>
                  </TableCell>
                </TableRow>
                {expanded && <AtRiskDrillDownRow milestoneId={m.id} />}
              </Fragment>
            );
          })}
          {milestones.length === 0 && !isLoading && (
            <TableRow>
              <TableCell colSpan={100} data-testid="milestone-list-empty" className="text-center text-muted-foreground py-8">
                No milestones yet. Click "New Milestone" to create one.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      <CreateMilestoneDialog open={createOpen} onOpenChange={setCreateOpen} />

      <EditMilestoneDialog
        milestone={editingMilestone}
        open={editingMilestone !== null}
        onOpenChange={(open) => {
          if (!open) setEditingMilestone(null);
        }}
      />

      <Dialog
        open={deletingMilestone !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDeletingMilestone(null);
            deleteMut.reset();
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Milestone</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete &ldquo;{deletingMilestone?.name}&rdquo;? Its Epics
              will be un-tagged, not deleted.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {deleteMut.isError && (
              <p className="text-sm text-destructive mr-auto">Failed to delete milestone.</p>
            )}
            <Button
              variant="ghost"
              onClick={() => {
                setDeletingMilestone(null);
                deleteMut.reset();
              }}
            >
              Cancel
            </Button>
            <Button
              data-testid="delete-milestone-confirm"
              variant="destructive"
              onClick={handleDelete}
              disabled={deleteMut.isPending}
            >
              {deleteMut.isPending ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </PageShell>
  );
}
