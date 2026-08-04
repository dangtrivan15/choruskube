import { useDroppable } from "@dnd-kit/core";
import type { StoryResponse, StoryStage } from "@/lib/types";
import { cn } from "@/lib/utils";
import StoryBoardCard from "./StoryBoardCard";

interface Props {
  stage: StoryStage;
  label: string;
  stories: StoryResponse[];
}

/**
 * One droppable column of the Story Board (backlog / in_progress /
 * rolled_out). Dropping a dragged StoryBoardCard here fires `onDragEnd` in
 * StoryBoardPage with this column's `stage` as the drop target.
 * Structurally parallel to the Task board's TaskBoardColumn (its closer
 * structural match, since both are flat, non-`readyOnly` boards) — this is a
 * sibling, not a generalization.
 */
export default function StoryBoardColumn({ stage, label, stories }: Props) {
  const { setNodeRef, isOver } = useDroppable({ id: stage });

  return (
    <div
      ref={setNodeRef}
      data-testid={`board-column-${stage}`}
      className={cn(
        "flex min-w-0 flex-1 flex-col gap-3 rounded-lg border bg-muted/20 p-3 transition-colors",
        isOver && "bg-muted/50 border-ring"
      )}
    >
      <div className="flex items-center justify-between px-1">
        <h3 className="text-sm font-semibold">{label}</h3>
        <span data-testid={`board-column-count-${stage}`} className="text-xs text-muted-foreground">
          {stories.length}
        </span>
      </div>

      <div className="flex flex-col gap-2">
        {stories.map((story) => (
          <StoryBoardCard key={story.id} story={story} />
        ))}
        {stories.length === 0 && (
          <div
            data-testid={`board-column-empty-${stage}`}
            className="rounded-md border border-dashed p-4 text-center text-xs text-muted-foreground"
          >
            No stories
          </div>
        )}
      </div>
    </div>
  );
}
