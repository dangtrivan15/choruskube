import { useMemo } from "react";
import { Link } from "react-router";
import { List } from "lucide-react";
import {
  DndContext,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import { useEpics, useUpdateEpicStage, EPIC_BOARD_PAGINATION } from "@/hooks/useEpics";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { EpicResponse, EpicStage } from "@/lib/types";
import { Skeleton } from "@/components/ui/skeleton";
import PageHeader from "@/components/layout/PageHeader";
import BoardColumn from "@/components/roadmap/BoardColumn";

const COLUMNS: { stage: EpicStage; label: string }[] = [
  { stage: "backlog", label: "Backlog" },
  { stage: "in_progress", label: "In Progress" },
  { stage: "rolled_out", label: "Rolled Out" },
];

/**
 * Roadmap Board — a Kanban view of Epics grouped into columns by `stage`.
 * Dragging a card to a new column PATCHes the stage (see useUpdateEpicStage);
 * a drop back into the same column is a no-op and never calls the mutation.
 */
export default function RoadmapBoardPage() {
  const { data: pageData, isLoading } = useEpics(undefined, EPIC_BOARD_PAGINATION);
  const updateStage = useUpdateEpicStage();
  useRoadmapSubscription();

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor)
  );

  const epics = pageData?.content;
  const byStage = useMemo(() => {
    const groups: Record<EpicStage, EpicResponse[]> = {
      backlog: [],
      in_progress: [],
      rolled_out: [],
    };
    for (const epic of epics ?? []) {
      groups[epic.stage].push(epic);
    }
    return groups;
  }, [epics]);

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over) return;

    const targetStage = over.id as EpicStage;
    const currentStage = active.data.current?.stage as EpicStage | undefined;
    // A drop back into the epic's own column must not call the mutation.
    if (!currentStage || currentStage === targetStage) return;

    updateStage.mutate({ id: String(active.id), stage: targetStage });
  }

  return (
    <div className="flex h-full min-w-0 flex-col p-4 md:p-6">
      <PageHeader title="Roadmap Board" data-testid="roadmap-board-heading">
        <Link
          to="/roadmap"
          data-testid="roadmap-board-list-view-link"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <List className="size-4" />
          List view
        </Link>
      </PageHeader>

      {isLoading && (
        <div className="mt-4 flex flex-1 gap-4">
          {COLUMNS.map((c) => (
            <Skeleton key={c.stage} className="h-full flex-1" />
          ))}
        </div>
      )}

      {!isLoading && (
        <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
          <div data-testid="roadmap-board" className="mt-4 flex flex-1 gap-4 overflow-x-auto">
            {COLUMNS.map((c) => (
              <BoardColumn key={c.stage} stage={c.stage} label={c.label} epics={byStage[c.stage]} />
            ))}
          </div>
        </DndContext>
      )}
    </div>
  );
}
