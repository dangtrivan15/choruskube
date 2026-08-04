import { useMemo } from "react";
import { Link } from "react-router";
import { Kanban, ListChecks, List } from "lucide-react";
import {
  DndContext,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from "@dnd-kit/core";
import { useAllStories, useUpdateStoryStage, STORY_BOARD_PAGINATION } from "@/hooks/useStories";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { StoryResponse, StoryStage } from "@/lib/types";
import { Skeleton } from "@/components/ui/skeleton";
import PageHeader from "@/components/layout/PageHeader";
import StoryBoardColumn from "@/components/roadmap/StoryBoardColumn";

const COLUMNS: { stage: StoryStage; label: string }[] = [
  { stage: "backlog", label: "Backlog" },
  { stage: "in_progress", label: "In Progress" },
  { stage: "rolled_out", label: "Rolled Out" },
];

/**
 * Story Board — a Kanban view of Stories grouped into columns by `stage`.
 * Story has its own persisted board `stage`, mirroring the Epic board
 * exactly — separate from the read-time `status` rollup, unlike the Task
 * board (which maps its columns directly onto `TaskResponse.status`).
 * Dragging a card to a new column PATCHes the stage (see
 * useUpdateStoryStage); a drop back into the same column is a no-op and
 * never calls the mutation.
 */
export default function StoryBoardPage() {
  const { data: pageData, isLoading } = useAllStories(undefined, STORY_BOARD_PAGINATION);
  const updateStage = useUpdateStoryStage();
  useRoadmapSubscription();

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor)
  );

  const stories = pageData?.content;
  const byStage = useMemo(() => {
    const groups: Record<StoryStage, StoryResponse[]> = {
      backlog: [],
      in_progress: [],
      rolled_out: [],
    };
    const knownStages = new Set(COLUMNS.map((c) => c.stage));
    for (const story of stories ?? []) {
      // `stage` is a Postgres enum extended via `ALTER TYPE ... ADD VALUE` (see CLAUDE.md); a
      // value the backend has already shipped but this build predates (e.g. mid rolling-deploy)
      // must not crash the whole app — skip it from the board rather than indexing `groups` with
      // an unrecognized key.
      if (!knownStages.has(story.stage)) {
        console.warn(`Story Board: skipping story ${story.id} with unrecognized stage "${story.stage}"`);
        continue;
      }
      groups[story.stage].push(story);
    }
    return groups;
  }, [stories]);

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over) return;

    const targetStage = over.id as StoryStage;
    const currentStage = active.data.current?.stage as StoryStage | undefined;
    // A drop back into the story's own column must not call the mutation.
    if (!currentStage || currentStage === targetStage) return;

    updateStage.mutate({ id: String(active.id), stage: targetStage });
  }

  return (
    <div className="flex h-full min-w-0 flex-col p-4 md:p-6">
      <PageHeader title="Story Board" data-testid="story-board-heading">
        <Link
          to="/roadmap/board"
          data-testid="story-board-epic-board-link"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <Kanban className="size-4" />
          Epic board
        </Link>
        <Link
          to="/roadmap/board/tasks"
          data-testid="story-board-task-board-link"
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
        >
          <ListChecks className="size-4" />
          Task board
        </Link>
        <Link
          to="/roadmap"
          data-testid="story-board-list-view-link"
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
          <div data-testid="story-board" className="mt-4 flex flex-1 gap-4 overflow-x-auto">
            {COLUMNS.map((c) => (
              <StoryBoardColumn
                key={c.stage}
                stage={c.stage}
                label={c.label}
                stories={byStage[c.stage]}
              />
            ))}
          </div>
        </DndContext>
      )}
    </div>
  );
}
