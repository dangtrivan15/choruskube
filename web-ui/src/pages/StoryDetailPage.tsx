import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { ArrowLeft, Trash2, Plus } from "lucide-react";
import Authorized from "@/components/Authorized";
import {
  useStory,
  useDeleteStory,
  useUpdateStoryPriority,
  useUpdateStoryTargetDate,
  useUpdateStoryStage,
} from "@/hooks/useStories";
import { useTasks } from "@/hooks/useTasks";
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
import CreateTaskDialog from "@/components/roadmap/CreateTaskDialog";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import RoadmapReadyToggle from "@/components/roadmap/RoadmapReadyToggle";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import PrioritySelect from "@/components/roadmap/PrioritySelect";
import TargetDateField from "@/components/roadmap/TargetDateField";
import LevelBadge from "@/components/roadmap/LevelBadge";
import StageBadge from "@/components/roadmap/StageBadge";
import RollOutPrompt from "@/components/roadmap/RollOutPrompt";
import PageHeader from "@/components/layout/PageHeader";

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

/** Story detail — shows the Story itself plus the Task list underneath it. */
export default function StoryDetailPage() {
  const { epicId, storyId } = useParams<{ epicId: string; storyId: string }>();
  const navigate = useNavigate();
  useRoadmapSubscription();

  const { data: story, isLoading } = useStory(storyId);
  const updateStoryStage = useUpdateStoryStage();
  const { data: tasks, isLoading: tasksLoading } = useTasks(storyId);
  const deleteStory = useDeleteStory(epicId ?? "");
  const updateStoryPriority = useUpdateStoryPriority();
  const updateStoryTargetDate = useUpdateStoryTargetDate();

  const [createTaskOpen, setCreateTaskOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [readyOnly, setReadyOnly] = useState(false);

  // "Ready to start" is the same predicate the server counts into `EpicResponse.readyItemCount`
  // (EpicReadinessAssembler#isStartable): still in backlog AND unblocked. Readiness alone would
  // keep finished Tasks in the list, since nothing upstream blocks work that is already done.
  const visibleTasks = readyOnly
    ? tasks?.filter((t) => t.status === "backlog" && t.readiness === "READY")
    : tasks;

  function handleDelete() {
    if (!storyId || !epicId) return;
    deleteStory.mutate(storyId, {
      onSuccess: () => {
        setDeleteOpen(false);
        navigate(`/roadmap/epics/${epicId}`);
      },
    });
  }

  if (isLoading || !story) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-6 w-1/3" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }

  return (
    <div className="min-w-0 space-y-6 p-4 md:p-6 max-w-3xl">
      <Link
        to={`/roadmap/epics/${epicId}`}
        className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft className="size-4" />
        Back to Epic
      </Link>

      <div className="flex min-w-0 items-start justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-2">
          <LevelBadge level="story" />
          <h2 data-testid="story-detail-title" className="text-xl font-semibold break-words">
            {story.title}
          </h2>
          <div className="flex flex-wrap items-center gap-2">
            <StageBadge stage={story.stage} data-testid="story-detail-stage" />
            <PriorityBadge priority={story.priority} data-testid="story-detail-priority-badge" />
            <TargetDateField value={story.targetDate} readOnly testId="story-detail-target-date" />
            <span data-testid="story-detail-progress" className="text-sm text-muted-foreground">
              {story.progress.doneTasks}/{story.progress.totalTasks} tasks done
            </span>
          </div>
          <Authorized require="canOperate">
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">Priority</span>
              <PrioritySelect
                value={story.priority}
                size="sm"
                disabled={updateStoryPriority.isPending}
                onChange={(p) => updateStoryPriority.mutate({ id: story.id, priority: p })}
                testId="story-detail-priority-select"
              />
            </div>
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">Target date</span>
              <TargetDateField
                value={story.targetDate}
                disabled={updateStoryTargetDate.isPending}
                onChange={(targetDate) => updateStoryTargetDate.mutate({ id: story.id, targetDate })}
                testId="story-detail-target-date-input"
              />
            </div>
          </Authorized>
        </div>
      </div>

      <div data-testid="story-detail-description">
        <h3 className="text-sm font-medium text-muted-foreground mb-2">Description</h3>
        <MarkdownViewer content={story.description} maxHeight="max-h-72" />
      </div>

      <RollOutPrompt
        stage={story.stage}
        progress={story.progress}
        pending={updateStoryStage.isPending}
        onRollOut={() => updateStoryStage.mutate({ id: story.id, stage: "rolled_out" })}
        testId="story-detail-roll-out"
      />

      {story.progress.startedTasks === 0 && (
        <div className="flex flex-wrap gap-2 pt-2 border-t">
          <Authorized require="canAdmin">
            <Button
              data-testid="story-delete-button"
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
        <PageHeader title="Tasks">
          <RoadmapReadyToggle checked={readyOnly} onChange={setReadyOnly} />
          <Authorized require="canOperate">
            <Button data-testid="new-task-button" size="sm" onClick={() => setCreateTaskOpen(true)}>
              <Plus className="size-4" />
              New Task
            </Button>
          </Authorized>
        </PageHeader>

        <div data-testid="task-list" className="mt-4">
          {tasksLoading &&
            Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-14 w-full mb-2" />)}
          {visibleTasks?.map((task) => (
            <Link
              key={task.id}
              to={`/tasks/${task.id}`}
              data-testid="task-item"
              className="flex items-center justify-between gap-4 p-3 border-b transition-colors hover:bg-muted/50"
            >
              <div className="min-w-0 flex-1">
                <TruncatedText as="div" className="font-medium text-sm">
                  {task.title}
                </TruncatedText>
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  {statusBadge(task.status)}
                  <PriorityBadge priority={task.priority} data-testid="task-item-priority-badge" />
                  <ReadinessBadge readiness={task.readiness} data-testid="task-item-readiness-badge" />
                </div>
              </div>
            </Link>
          ))}
          {tasks && tasks.length === 0 && (
            <div data-testid="task-list-empty" className="p-6 text-center text-muted-foreground text-sm">
              No tasks yet. Click &ldquo;New Task&rdquo; to create one.
            </div>
          )}
          {tasks && tasks.length > 0 && visibleTasks?.length === 0 && (
            <div data-testid="task-list-empty" className="p-6 text-center text-muted-foreground text-sm">
              No tasks are ready to start. Try turning off the &ldquo;Ready to start&rdquo; filter.
            </div>
          )}
        </div>
      </div>

      <CreateTaskDialog storyId={story.id} open={createTaskOpen} onOpenChange={setCreateTaskOpen} />

      <Dialog
        open={deleteOpen}
        onOpenChange={(open) => {
          setDeleteOpen(open);
          if (!open) deleteStory.reset();
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Story</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete &ldquo;{story.title}&rdquo;? This action cannot be
              undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {deleteStory.isError && (
              <p className="text-sm text-destructive mr-auto">Failed to delete.</p>
            )}
            <Button variant="ghost" onClick={() => setDeleteOpen(false)}>
              Cancel
            </Button>
            <Button
              data-testid="delete-story-confirm"
              variant="destructive"
              onClick={handleDelete}
              disabled={deleteStory.isPending}
            >
              {deleteStory.isPending ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
