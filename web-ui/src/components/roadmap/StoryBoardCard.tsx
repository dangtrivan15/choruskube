import { useState } from "react";
import { Link } from "react-router";
import { useDraggable } from "@dnd-kit/core";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useTasks } from "@/hooks/useTasks";
import type { StoryResponse } from "@/lib/types";
import { Card, CardHeader, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import { roadmapLevelMeta } from "@/lib/roadmapLevel";
import { cn } from "@/lib/utils";

const levelMeta = roadmapLevelMeta("story");

interface Props {
  story: StoryResponse;
}

/**
 * A single Story Board card. Draggable via dnd-kit (`useDraggable`); the
 * drop is resolved by StoryBoardPage's `DndContext`. Expanding lazily
 * fetches the Story's Tasks — mirrors EpicBoardCard's expand pattern one
 * level down the hierarchy: `hasLoadedTasks` stays true once tripped so
 * collapsing/re-expanding reuses the cached `useTasks` query instead of
 * re-fetching (only the *visibility* of the task list toggles on `expanded`).
 */
export default function StoryBoardCard({ story }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [hasLoadedTasks, setHasLoadedTasks] = useState(false);

  const { data: tasks, isLoading: tasksLoading } = useTasks(
    hasLoadedTasks ? story.id : undefined
  );

  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: story.id,
    data: { stage: story.stage },
  });

  const style = transform
    ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` }
    : undefined;

  function toggleExpand() {
    setExpanded((prev) => !prev);
    if (!hasLoadedTasks) setHasLoadedTasks(true);
  }

  return (
    <Card
      ref={setNodeRef}
      style={style}
      data-testid="story-board-card"
      data-story-id={story.id}
      className={cn("touch-none select-none", isDragging && "opacity-50")}
      {...listeners}
      {...attributes}
    >
      <CardHeader className="gap-1 p-3 pb-2">
        <div className="flex items-start justify-between gap-2">
          <span className="flex min-w-0 flex-1 items-center gap-1.5">
            <levelMeta.Icon className={cn("size-3.5 shrink-0", levelMeta.textClass)} />
            <Link
              to={`/roadmap/epics/${story.epicId}/stories/${story.id}`}
              data-testid="story-board-card-title"
              className="min-w-0 truncate text-sm font-medium hover:underline"
              // Suppresses the browser's own link-drag so dnd-kit sees the press — see
              // EpicBoardCard's title for why this is `draggable={false}` and not a `pointerdown`
              // guard. No `onClick` guard needed here: unlike EpicBoardCard this card has no root
              // `onClick` for the click to collide with.
              draggable={false}
            >
              {story.title}
            </Link>
          </span>
          <button
            type="button"
            data-testid="story-board-card-expand"
            className="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            aria-expanded={expanded}
            aria-label={expanded ? "Collapse tasks" : "Expand tasks"}
            onPointerDown={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              toggleExpand();
            }}
          >
            {expanded ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <PriorityBadge priority={story.priority} size="compact" data-testid="story-board-card-priority" />
          <span data-testid="story-board-card-progress" className="text-xs text-muted-foreground">
            {story.progress.doneTasks} of {story.progress.totalTasks} tasks complete
          </span>
        </div>
      </CardHeader>

      {expanded && (
        <CardContent
          data-testid="story-board-card-tasks"
          className="flex flex-col gap-1.5 p-3 pt-0"
        >
          {tasksLoading && <Skeleton className="h-4 w-full" />}
          {tasks?.map((task) => (
            <div
              key={task.id}
              data-testid="story-board-card-task"
              className="flex items-center justify-between gap-2 rounded border bg-background/50 px-2 py-1 text-xs"
            >
              <span className="min-w-0 flex-1 truncate">{task.title}</span>
              <div className="flex shrink-0 items-center gap-1.5">
                <ReadinessBadge
                  readiness={task.readiness}
                  size="compact"
                  data-testid="story-board-card-task-blocked"
                />
                <span data-testid="story-board-card-task-status" className="text-muted-foreground">
                  {task.status}
                </span>
              </div>
            </div>
          ))}
          {tasks && tasks.length === 0 && (
            <p className="text-xs text-muted-foreground">No tasks yet.</p>
          )}
        </CardContent>
      )}
    </Card>
  );
}
