import { useState } from "react";
import { Link, useParams } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { ArrowLeft, Pencil, Trash2, Plus, GitBranch, Layers } from "lucide-react";
import Authorized from "@/components/Authorized";
import {
  useEpic,
  useDeleteEpic,
  useUpdateEpicPriority,
  useUpdateEpicTargetDate,
  useUpdateEpicStage,
} from "@/hooks/useEpics";
import { useAssignEpicMilestone } from "@/hooks/useMilestones";
import { useStories } from "@/hooks/useStories";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { SortParam, Priority } from "@/lib/types";
import { priorityMeta } from "@/lib/priorityMeta";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import TruncatedText from "@/components/ui/TruncatedText";
import SortDropdown from "@/components/ui/SortDropdown";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import EditEpicDialog from "@/components/roadmap/EditEpicDialog";
import CreateStoryDialog from "@/components/roadmap/CreateStoryDialog";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import RoadmapReadyToggle from "@/components/roadmap/RoadmapReadyToggle";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import PrioritySelect from "@/components/roadmap/PrioritySelect";
import PriorityFilter from "@/components/roadmap/PriorityFilter";
import MilestoneBadge from "@/components/roadmap/MilestoneBadge";
import MilestoneSelect from "@/components/roadmap/MilestoneSelect";
import TargetDateField from "@/components/roadmap/TargetDateField";
import LevelBadge from "@/components/roadmap/LevelBadge";
import StageBadge from "@/components/roadmap/StageBadge";
import RollOutPrompt from "@/components/roadmap/RollOutPrompt";
import PageHeader from "@/components/layout/PageHeader";
import { useNavigate } from "react-router";

// Client-side Story sort options for the embedded list (the Story list here is
// fetched whole via useStories, not paginated) — priority only, ranked by
// priorityMeta's `order`. Mirrors RoadmapPage's server-side priority sort, but
// applied in-memory since there is no list endpoint call to attach `?sort=` to.
const STORY_SORT_OPTIONS = [
  { label: "Priority (High→Low)", field: "priority", direction: "desc" as const },
  { label: "Priority (Low→High)", field: "priority", direction: "asc" as const },
];


/** Epic detail — shows the Epic itself plus the Story list underneath it. */
export default function EpicDetailPage() {
  const { epicId } = useParams<{ epicId: string }>();
  const navigate = useNavigate();
  useRoadmapSubscription();

  const { data: epic, isLoading } = useEpic(epicId);
  // `false`: this page has no "ready to start" filter, so the hook's optimistic write targets the
  // unfiltered board cache. The detail view itself refreshes off the hook's ["epics"] invalidation.
  const updateEpicStage = useUpdateEpicStage(false);
  const { data: stories, isLoading: storiesLoading } = useStories(epicId);
  const deleteEpic = useDeleteEpic();
  const updateEpicPriority = useUpdateEpicPriority();
  const updateEpicTargetDate = useUpdateEpicTargetDate();
  const assignMilestone = useAssignEpicMilestone();

  const [editOpen, setEditOpen] = useState(false);
  const [createStoryOpen, setCreateStoryOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [readyOnly, setReadyOnly] = useState(false);
  const [priorityFilter, setPriorityFilter] = useState<Priority | undefined>(undefined);
  const [storySort, setStorySort] = useState<SortParam | null>(null);

  // Client-side filter + sort over the whole embedded Story list — mirrors the
  // existing `readyOnly` filter above, chained with the priority filter, then
  // the priority sort (by priorityMeta's `order`).
  const visibleStories = (() => {
    let list = stories;
    if (!list) return list;
    if (readyOnly) list = list.filter((s) => s.readiness === "READY");
    if (priorityFilter) list = list.filter((s) => s.priority === priorityFilter);
    if (storySort?.field === "priority") {
      const dir = storySort.direction;
      list = [...list].sort((a, b) => {
        const oa = priorityMeta(a.priority)?.order ?? 0;
        const ob = priorityMeta(b.priority)?.order ?? 0;
        return dir === "desc" ? ob - oa : oa - ob;
      });
    }
    return list;
  })();

  function handleDelete() {
    if (!epicId) return;
    deleteEpic.mutate(epicId, {
      onSuccess: () => {
        setDeleteOpen(false);
        navigate("/roadmap");
      },
    });
  }

  if (isLoading || !epic) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-6 w-1/3" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }

  const sp = epic.softwareProject;
  const Icon = sp.type === "repo_group" ? Layers : GitBranch;

  return (
    <div className="min-w-0 space-y-6 p-4 md:p-6 max-w-3xl">
      <Link
        to="/roadmap"
        className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-4" />
        Back to Roadmap
      </Link>

      <div className="flex min-w-0 items-start justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-2">
          <LevelBadge level="epic" />
          <h2 data-testid="epic-detail-title" className="text-xl font-semibold break-words">
            {epic.title}
          </h2>
          <div className="flex flex-wrap items-center gap-2">
            <StageBadge stage={epic.stage} data-testid="epic-detail-stage" />
            <PriorityBadge priority={epic.priority} data-testid="epic-detail-priority-badge" />
            <MilestoneBadge milestone={epic.milestone} data-testid="epic-detail-milestone-badge" />
            <TargetDateField value={epic.targetDate} readOnly testId="epic-detail-target-date" />
            <span data-testid="epic-detail-progress" className="text-sm text-muted-foreground">
              {epic.progress.doneTasks}/{epic.progress.totalTasks} tasks done
            </span>
            <span className="text-sm text-muted-foreground">
              {formatDistanceToNow(new Date(epic.createdAt), { addSuffix: true })}
            </span>
          </div>
          <Authorized require="canOperate">
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">Priority</span>
              <PrioritySelect
                value={epic.priority}
                size="sm"
                disabled={updateEpicPriority.isPending}
                onChange={(p) => updateEpicPriority.mutate({ id: epic.id, priority: p })}
                testId="epic-detail-priority-select"
              />
            </div>
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">Target date</span>
              <TargetDateField
                value={epic.targetDate}
                disabled={updateEpicTargetDate.isPending}
                onChange={(targetDate) => updateEpicTargetDate.mutate({ id: epic.id, targetDate })}
                testId="epic-detail-target-date-input"
              />
            </div>
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">Milestone</span>
              <MilestoneSelect
                value={epic.milestone?.id ?? null}
                softwareProjectId={epic.softwareProject.id}
                disabled={assignMilestone.isPending}
                onChange={(milestoneId) => assignMilestone.mutate({ id: epic.id, milestoneId })}
                size="sm"
                testId="epic-detail-milestone-select"
              />
            </div>
          </Authorized>
          <div data-testid="epic-software-project" className="flex flex-wrap items-center gap-2">
            <span
              data-testid="epic-software-project-chip"
              title={sp.name}
              className="inline-flex min-w-0 max-w-full items-center gap-1.5 rounded-md border bg-muted/40 px-2 py-1 text-xs font-medium"
            >
              <Icon className="size-3.5 shrink-0" />
              <span className="min-w-0 flex-1 truncate">{sp.name}</span>
            </span>
            {(epic.repos ?? []).map((r) => (
              <a
                key={r.id}
                data-testid="epic-repo-pill"
                href={r.url}
                target="_blank"
                rel="noopener noreferrer"
                title={r.name}
                className="inline-block max-w-full truncate rounded-full border px-2 py-0.5 text-xs text-muted-foreground hover:text-foreground hover:bg-muted/50"
              >
                {r.name}
              </a>
            ))}
          </div>
        </div>
      </div>

      <div data-testid="epic-detail-description">
        <h3 className="text-sm font-medium text-muted-foreground mb-2">Description</h3>
        <MarkdownViewer content={epic.description} maxHeight="max-h-72" />
      </div>

      {epic.motivation && (
        <div>
          <h3 className="text-sm font-medium text-muted-foreground mb-2">Motivation</h3>
          <MarkdownViewer content={epic.motivation} maxHeight="max-h-48" />
        </div>
      )}

      <RollOutPrompt
        stage={epic.stage}
        progress={epic.progress}
        pending={updateEpicStage.isPending}
        onRollOut={() => updateEpicStage.mutate({ id: epic.id, stage: "rolled_out" })}
        testId="epic-detail-roll-out"
      />

      {epic.progress.startedTasks === 0 && (
        <div className="flex flex-wrap gap-2 pt-2 border-t">
          <Authorized require="canAdmin">
            <Button data-testid="epic-edit-button" variant="ghost" size="sm" onClick={() => setEditOpen(true)}>
              <Pencil className="size-4" />
              Edit
            </Button>
            <Button
              data-testid="epic-delete-button"
              variant="ghost"
              size="sm"
              onClick={() => setDeleteOpen(true)}
            >
              <Trash2 className="size-4" />
              Delete
            </Button>
          </Authorized>
        </div>
      )}

      <div className="pt-4 border-t">
        <PageHeader title="Stories">
          <RoadmapReadyToggle checked={readyOnly} onChange={setReadyOnly} />
          <PriorityFilter value={priorityFilter} onChange={setPriorityFilter} />
          <SortDropdown options={STORY_SORT_OPTIONS} currentSort={storySort} onSort={setStorySort} />
          <Authorized require="canOperate">
            <Button data-testid="new-story-button" size="sm" onClick={() => setCreateStoryOpen(true)}>
              <Plus className="size-4" />
              New Story
            </Button>
          </Authorized>
        </PageHeader>

        <div data-testid="story-list" className="mt-4">
          {storiesLoading &&
            Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-14 w-full mb-2" />)}
          {visibleStories?.map((story) => (
            <Link
              key={story.id}
              to={`/roadmap/epics/${epic.id}/stories/${story.id}`}
              data-testid="story-item"
              className="flex items-center justify-between gap-4 p-3 border-b transition-colors hover:bg-muted/50"
            >
              <div className="min-w-0 flex-1">
                <TruncatedText as="div" className="font-medium text-sm">
                  {story.title}
                </TruncatedText>
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  <StageBadge stage={story.stage} data-testid="story-item-stage" />
                  <PriorityBadge priority={story.priority} size="compact" data-testid="story-item-priority-badge" />
                  <ReadinessBadge readiness={story.readiness} data-testid="story-item-readiness-badge" />
                  <span className="text-xs text-muted-foreground">
                    {story.progress.doneTasks}/{story.progress.totalTasks} tasks done
                  </span>
                </div>
              </div>
            </Link>
          ))}
          {stories && stories.length === 0 && (
            <div data-testid="story-list-empty" className="p-6 text-center text-muted-foreground text-sm">
              No stories yet. Click &ldquo;New Story&rdquo; to create one.
            </div>
          )}
          {stories && stories.length > 0 && visibleStories?.length === 0 && (
            <div data-testid="story-list-empty" className="p-6 text-center text-muted-foreground text-sm">
              {readyOnly && priorityFilter ? (
                <>
                  No stories match the current filters. Try turning off the &ldquo;Ready to start&rdquo; filter or
                  clearing the priority filter.
                </>
              ) : priorityFilter ? (
                <>No stories match the selected priority. Try clearing the priority filter.</>
              ) : (
                <>No stories are ready to start. Try turning off the &ldquo;Ready to start&rdquo; filter.</>
              )}
            </div>
          )}
        </div>
      </div>

      <EditEpicDialog epic={editOpen ? epic : null} open={editOpen} onOpenChange={setEditOpen} />
      <CreateStoryDialog epicId={epic.id} open={createStoryOpen} onOpenChange={setCreateStoryOpen} />

      <Dialog
        open={deleteOpen}
        onOpenChange={(open) => {
          setDeleteOpen(open);
          if (!open) deleteEpic.reset();
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Epic</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete &ldquo;{epic.title}&rdquo;? This action cannot be
              undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {deleteEpic.isError && (
              <p className="text-sm text-destructive mr-auto">Failed to delete.</p>
            )}
            <Button variant="ghost" onClick={() => setDeleteOpen(false)}>
              Cancel
            </Button>
            <Button
              data-testid="delete-epic-confirm"
              variant="destructive"
              onClick={handleDelete}
              disabled={deleteEpic.isPending}
            >
              {deleteEpic.isPending ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
