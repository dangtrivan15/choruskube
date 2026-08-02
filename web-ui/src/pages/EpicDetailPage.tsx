import { useState } from "react";
import { Link, useParams } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { ArrowLeft, Pencil, Trash2, Plus, GitBranch, Layers } from "lucide-react";
import Authorized from "@/components/Authorized";
import { useEpic, useDeleteEpic } from "@/hooks/useEpics";
import { useStories } from "@/hooks/useStories";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import TruncatedText from "@/components/ui/TruncatedText";
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
import PageHeader from "@/components/layout/PageHeader";
import { useNavigate } from "react-router";

function statusBadge(status: string) {
  switch (status) {
    case "backlog":
      return <Badge variant="outline">backlog</Badge>;
    case "in_progress":
      return <Badge variant="secondary">in progress</Badge>;
    case "done":
      return <Badge variant="default">done</Badge>;
    default:
      return <Badge variant="outline">{status}</Badge>;
  }
}

/** Epic detail — shows the Epic itself plus the Story list underneath it. */
export default function EpicDetailPage() {
  const { epicId } = useParams<{ epicId: string }>();
  const navigate = useNavigate();
  useRoadmapSubscription();

  const { data: epic, isLoading } = useEpic(epicId);
  const { data: stories, isLoading: storiesLoading } = useStories(epicId);
  const deleteEpic = useDeleteEpic();

  const [editOpen, setEditOpen] = useState(false);
  const [createStoryOpen, setCreateStoryOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [readyOnly, setReadyOnly] = useState(false);

  const visibleStories = readyOnly ? stories?.filter((s) => s.readiness === "READY") : stories;

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
          <h2 data-testid="epic-detail-title" className="text-xl font-semibold break-words">
            {epic.title}
          </h2>
          <div className="flex flex-wrap items-center gap-2">
            <span data-testid="epic-detail-status">{statusBadge(epic.status)}</span>
            <span data-testid="epic-detail-progress" className="text-sm text-muted-foreground">
              {epic.progress.doneTasks}/{epic.progress.totalTasks} tasks done
            </span>
            <span className="text-sm text-muted-foreground">
              {formatDistanceToNow(new Date(epic.createdAt), { addSuffix: true })}
            </span>
          </div>
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

      {epic.status === "backlog" && (
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
                  {statusBadge(story.status)}
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
              No stories are ready to start. Try turning off the &ldquo;Ready to start&rdquo; filter.
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
