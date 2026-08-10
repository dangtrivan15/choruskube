import { useDroppable } from "@dnd-kit/core";
import type { EpicResponse, EpicStage } from "@/lib/types";
import { cn } from "@/lib/utils";
import EpicBoardCard from "./EpicBoardCard";

interface Props {
  stage: EpicStage;
  label: string;
  epics: EpicResponse[];
  /** Whether the "Ready to start" filter is active — changes the empty-column copy. */
  readyOnly?: boolean;
  /** The currently-focused Epic/Story (§3.1), forwarded to each card so it can highlight/expand itself. */
  focusedEpicId?: string;
  focusedStoryId?: string;
  onFocusEpic?: (epicId: string) => void;
  /** Ref-callback factory, curried per Epic id, so the page can locate a specific card's DOM node. */
  cardRef?: (epicId: string) => (node: HTMLDivElement | null) => void;
}

/**
 * One droppable column of the Roadmap Board (backlog / in_progress /
 * rolled_out). Dropping a dragged EpicBoardCard here fires `onDragEnd` in
 * RoadmapBoardPage with this column's `stage` as the drop target.
 */
export default function BoardColumn({
  stage,
  label,
  epics,
  readyOnly = false,
  focusedEpicId,
  focusedStoryId,
  onFocusEpic,
  cardRef,
}: Props) {
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
          {epics.length}
        </span>
      </div>

      <div className="flex flex-col gap-2">
        {epics.map((epic) => {
          const isFocused = epic.id === focusedEpicId;
          return (
            <EpicBoardCard
              key={epic.id}
              epic={epic}
              isFocused={isFocused}
              initiallyExpanded={isFocused && Boolean(focusedStoryId)}
              focusedStoryId={focusedStoryId}
              onFocus={onFocusEpic}
              cardRef={cardRef?.(epic.id)}
            />
          );
        })}
        {epics.length === 0 && (
          <div
            data-testid={`board-column-empty-${stage}`}
            className="rounded-md border border-dashed p-4 text-center text-xs text-muted-foreground"
          >
            {readyOnly ? "Nothing ready in this stage" : "No epics"}
          </div>
        )}
      </div>
    </div>
  );
}
