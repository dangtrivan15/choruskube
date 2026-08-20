import { useDraggable } from "@dnd-kit/core";
import { Link } from "react-router";
import type { TaskResponse } from "@/lib/types";
import { Card, CardHeader } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import RunStatusBadge from "@/components/runs/RunStatusBadge";
import { roadmapLevelMeta } from "@/lib/roadmapLevel";
import { cn } from "@/lib/utils";

const levelMeta = roadmapLevelMeta("task");

interface Props {
  task: TaskResponse;
}

function statusBadge(status: "backlog" | "in_progress" | "done") {
  switch (status) {
    case "backlog":
      return <Badge variant="outline">backlog</Badge>;
    case "in_progress":
      return <Badge variant="secondary">in progress</Badge>;
    case "done":
      return <Badge variant="default">done</Badge>;
  }
}

/**
 * A single Task Board card. Draggable via dnd-kit (`useDraggable`); the drop
 * is resolved by TaskBoardPage's `DndContext`. Unlike EpicBoardCard, a Task
 * has no nested children to expand — it's the leaf of the roadmap hierarchy —
 * so this is a flat, non-expandable summary showing the title, its `status`
 * badge, and (if present) its most recent run's status.
 *
 * Deliberately does not render a readiness badge: the listing endpoint's
 * shared mapper hardcodes `TaskResponse.readiness` to `null`, so a badge here
 * could never show a value. (Story/Task rows elsewhere in the app do show
 * readiness badges from a different, unrelated code path.)
 */
export default function TaskBoardCard({ task }: Props) {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: task.id,
    data: { status: task.status },
  });

  const style = transform
    ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` }
    : undefined;

  return (
    <Card
      ref={setNodeRef}
      style={style}
      data-testid="task-board-card"
      data-task-id={task.id}
      className={cn("touch-none select-none", isDragging && "opacity-50")}
      {...listeners}
      {...attributes}
    >
      <CardHeader className="gap-1 p-3 pb-2">
        <span className="flex min-w-0 items-center gap-1.5">
          <levelMeta.Icon className={cn("size-3.5 shrink-0", levelMeta.textClass)} />
          <Link
            to={`/tasks/${task.id}`}
            data-testid="task-board-card-title"
            className="min-w-0 truncate text-sm font-medium hover:underline"
            // Keeps the title out of the card's drag surface — see EpicBoardCard's title for
            // why a link inside a dnd-kit draggable must not be droppable-on (dnd-kit's click
            // suppression stops propagation but never the `<a>`'s default navigation).
            draggable={false}
            onPointerDown={(e) => e.stopPropagation()}
          >
            {task.title}
          </Link>
        </span>
        <span data-testid="task-board-card-status" className="flex items-center gap-1.5">
          {statusBadge(task.status)}
          {task.latestRunStatus && (
            <span data-testid="task-board-card-run-status">
              <RunStatusBadge status={task.latestRunStatus} />
            </span>
          )}
        </span>
      </CardHeader>
    </Card>
  );
}
