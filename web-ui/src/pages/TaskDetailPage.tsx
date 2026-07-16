import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { ArrowLeft, Trash2, Play, CheckCircle2, GitBranch, Layers } from "lucide-react";
import Authorized from "@/components/Authorized";
import { useTask, useDeleteTask, useStartTask, useCompleteTask } from "@/hooks/useTasks";
import { useTaskRuns } from "@/hooks/useTaskRuns";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import TaskRunHistoryList from "@/components/roadmap/TaskRunHistoryList";
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

/**
 * Task detail — the leaf of the hierarchy. Shows the Task itself, its full
 * run history (Decision 1), and Start/Restart/Complete actions gated on the
 * same rules the flat Roadmap used to apply to a proposal.
 */
export default function TaskDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  useRoadmapSubscription();

  const { data: task, isLoading } = useTask(id);
  const { data: runsPage, isLoading: runsLoading } = useTaskRuns(id);
  const deleteTask = useDeleteTask(task?.storyId ?? "");
  const startTask = useStartTask();
  const completeTask = useCompleteTask();

  const [deleteOpen, setDeleteOpen] = useState(false);
  const [startOpen, setStartOpen] = useState(false);

  function handleDelete() {
    if (!id) return;
    deleteTask.mutate(id, {
      onSuccess: () => {
        setDeleteOpen(false);
        navigate(-1);
      },
    });
  }

  function handleStart() {
    if (!id) return;
    startTask.mutate(id, { onSuccess: () => setStartOpen(false) });
  }

  function handleComplete() {
    if (!id) return;
    completeTask.mutate(id);
  }

  if (isLoading || !task) {
    return (
      <div className="p-6 space-y-4">
        <Skeleton className="h-6 w-1/3" />
        <Skeleton className="h-24 w-full" />
      </div>
    );
  }

  const canRestart =
    task.status === "in_progress" &&
    task.latestRunStatus != null &&
    (task.latestRunStatus === "failed" || task.latestRunStatus === "cancelled");

  const canComplete = task.status === "in_progress" && task.latestRunStatus === "completed";

  const sp = task.softwareProject;
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
          <h2 data-testid="task-detail-title" className="text-xl font-semibold break-words">
            {task.title}
          </h2>
          <div className="flex flex-wrap items-center gap-2">
            <span data-testid="task-detail-status">{statusBadge(task.status)}</span>
            <span
              data-testid="task-software-project-chip"
              className="inline-flex items-center gap-1.5 rounded-md border bg-muted/40 px-2 py-1 text-xs font-medium"
            >
              <Icon className="size-3.5 shrink-0" />
              {sp.name}
            </span>
          </div>
        </div>
      </div>

      <div data-testid="task-detail-description">
        <h3 className="text-sm font-medium text-muted-foreground mb-2">Description</h3>
        <MarkdownViewer content={task.description} maxHeight="max-h-72" />
      </div>

      <div className="flex flex-wrap gap-2 pt-2 border-t">
        {task.status === "backlog" && (
          <>
            <Authorized require="canOperate">
              <Button data-testid="task-start-button" size="sm" onClick={() => setStartOpen(true)}>
                <Play className="size-4" />
                Start
              </Button>
            </Authorized>
            <Authorized require="canAdmin">
              <Button
                data-testid="task-delete-button"
                variant="ghost"
                size="sm"
                onClick={() => setDeleteOpen(true)}
              >
                <Trash2 className="size-4" />
                Delete
              </Button>
            </Authorized>
          </>
        )}
        {task.status === "in_progress" && (
          <Authorized require="canOperate">
            {canRestart && (
              <Button data-testid="task-restart-button" size="sm" onClick={() => setStartOpen(true)}>
                <Play className="size-4" />
                Restart
              </Button>
            )}
            <Button
              data-testid="task-complete-button"
              size="sm"
              onClick={handleComplete}
              disabled={!canComplete || completeTask.isPending}
            >
              <CheckCircle2 className="size-4" />
              {completeTask.isPending ? "Completing..." : "Complete"}
            </Button>
          </Authorized>
        )}
      </div>
      {completeTask.isError && (
        <p className="text-sm text-destructive">Failed to complete.</p>
      )}

      <div className="pt-4 border-t">
        <PageHeader title="Run History" />
        <div className="mt-4">
          <TaskRunHistoryList runs={runsPage?.content} isLoading={runsLoading} />
        </div>
      </div>

      <Dialog
        open={deleteOpen}
        onOpenChange={(open) => {
          setDeleteOpen(open);
          if (!open) deleteTask.reset();
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Task</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete &ldquo;{task.title}&rdquo;? This action cannot be
              undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {deleteTask.isError && (
              <p className="text-sm text-destructive mr-auto">Failed to delete.</p>
            )}
            <Button variant="ghost" onClick={() => setDeleteOpen(false)}>
              Cancel
            </Button>
            <Button
              data-testid="delete-task-confirm"
              variant="destructive"
              onClick={handleDelete}
              disabled={deleteTask.isPending}
            >
              {deleteTask.isPending ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={startOpen}
        onOpenChange={(open) => {
          setStartOpen(open);
          if (!open) startTask.reset();
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Start Feature Development</DialogTitle>
            <DialogDescription>
              This will create a workflow run for &ldquo;{task.title}&rdquo; using the selected
              repository. Continue?
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {startTask.isError && (
              <p className="text-sm text-destructive mr-auto">
                Failed to start. Check that the repository is valid.
              </p>
            )}
            <Button variant="ghost" onClick={() => setStartOpen(false)}>
              Cancel
            </Button>
            <Button data-testid="start-task-confirm" onClick={handleStart} disabled={startTask.isPending}>
              {startTask.isPending ? "Starting..." : "Start Run"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
