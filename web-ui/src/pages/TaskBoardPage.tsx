import { useMemo } from "react";
import {
  DndContext,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import { useAllTasks, useUpdateTaskStatus, TASK_BOARD_PAGINATION } from "@/hooks/useTasks";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { TaskResponse } from "@/lib/types";
import { Skeleton } from "@/components/ui/skeleton";
import PageHeader from "@/components/layout/PageHeader";
import TaskBoardColumn from "@/components/roadmap/TaskBoardColumn";
import RoadmapViewControls from "@/components/roadmap/RoadmapViewControls";

const COLUMNS: { status: "backlog" | "in_progress" | "done"; label: string }[] = [
  { status: "backlog", label: "Backlog" },
  { status: "in_progress", label: "In Progress" },
  { status: "done", label: "Done" },
];

/**
 * Task Board — a Kanban view of Tasks grouped into columns by `status`.
 * Unlike the Epic board (which has its own `stage` field, independent of the
 * read-time `status` rollup), Task has no separate stage concept: the board's
 * columns map directly onto `TaskResponse.status`. Dragging a card to a new
 * column PATCHes the status via the existing validated-transition endpoint
 * (see useUpdateTaskStatus, which does not duplicate that server-side
 * whitelist); a drop back into the same column is a no-op and never calls
 * the mutation.
 */
export default function TaskBoardPage() {
  const { data: pageData, isLoading } = useAllTasks(undefined, TASK_BOARD_PAGINATION);
  const updateStatus = useUpdateTaskStatus();
  useRoadmapSubscription();

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor)
  );

  const tasks = pageData?.content;
  const byStatus = useMemo(() => {
    const groups: Record<"backlog" | "in_progress" | "done", TaskResponse[]> = {
      backlog: [],
      in_progress: [],
      done: [],
    };
    const knownStatuses = new Set(COLUMNS.map((c) => c.status));
    for (const task of tasks ?? []) {
      // `status` (work_item_status) is a Postgres enum extended via `ALTER TYPE ... ADD
      // VALUE` (see CLAUDE.md); a value the backend has already shipped but this build
      // predates (e.g. mid rolling-deploy) must not crash the whole app — skip it from
      // the board rather than indexing `groups` with an unrecognized key.
      if (!knownStatuses.has(task.status)) {
        console.warn(`Task Board: skipping task ${task.id} with unrecognized status "${task.status}"`);
        continue;
      }
      groups[task.status].push(task);
    }
    return groups;
  }, [tasks]);

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over) return;

    const targetStatus = over.id as "backlog" | "in_progress" | "done";
    const currentStatus = active.data.current?.status as "backlog" | "in_progress" | "done" | undefined;
    // A drop back into the task's own column must not call the mutation.
    if (!currentStatus || currentStatus === targetStatus) return;

    updateStatus.mutate({ id: String(active.id), status: targetStatus });
  }

  return (
    <div className="flex h-full min-w-0 flex-col p-4 md:p-6">
      <PageHeader title="Task Board" data-testid="task-board-heading">
        <RoadmapViewControls level="task" view="board" />
      </PageHeader>

      {isLoading && (
        <div className="mt-4 flex flex-1 gap-4">
          {COLUMNS.map((c) => (
            <Skeleton key={c.status} className="h-full flex-1" />
          ))}
        </div>
      )}

      {!isLoading && (
        <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
          <div data-testid="task-board" className="mt-4 flex flex-1 gap-4 overflow-x-auto">
            {COLUMNS.map((c) => (
              <TaskBoardColumn
                key={c.status}
                status={c.status}
                label={c.label}
                tasks={byStatus[c.status]}
              />
            ))}
          </div>
        </DndContext>
      )}
    </div>
  );
}
