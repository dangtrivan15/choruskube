import { useDroppable } from "@dnd-kit/core";
import type { TaskResponse } from "@/lib/types";
import { cn } from "@/lib/utils";
import TaskBoardCard from "./TaskBoardCard";

interface Props {
  status: "backlog" | "in_progress" | "done";
  label: string;
  tasks: TaskResponse[];
}

/**
 * One droppable column of the Task Board (backlog / in_progress / done).
 * Dropping a dragged TaskBoardCard here fires `onDragEnd` in TaskBoardPage
 * with this column's `status` as the drop target. Structurally parallel to
 * the Epic board's BoardColumn — this is a sibling, not a generalization,
 * since Task's board column maps directly onto `status` rather than a
 * separate "stage" field.
 */
export default function TaskBoardColumn({ status, label, tasks }: Props) {
  const { setNodeRef, isOver } = useDroppable({ id: status });

  return (
    <div
      ref={setNodeRef}
      data-testid={`board-column-${status}`}
      className={cn(
        "flex min-w-0 flex-1 flex-col gap-3 rounded-lg border bg-muted/20 p-3 transition-colors",
        isOver && "bg-muted/50 border-ring"
      )}
    >
      <div className="flex items-center justify-between px-1">
        <h3 className="text-sm font-semibold">{label}</h3>
        <span data-testid={`board-column-count-${status}`} className="text-xs text-muted-foreground">
          {tasks.length}
        </span>
      </div>

      <div className="flex flex-col gap-2">
        {tasks.map((task) => (
          <TaskBoardCard key={task.id} task={task} />
        ))}
        {tasks.length === 0 && (
          <div className="rounded-md border border-dashed p-4 text-center text-xs text-muted-foreground">
            No tasks
          </div>
        )}
      </div>
    </div>
  );
}
