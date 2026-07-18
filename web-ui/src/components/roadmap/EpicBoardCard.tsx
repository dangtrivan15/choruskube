import { useState } from "react";
import { useDraggable } from "@dnd-kit/core";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useStories } from "@/hooks/useStories";
import type { EpicResponse } from "@/lib/types";
import { Card, CardHeader, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

interface Props {
  epic: EpicResponse;
}

/**
 * A single Roadmap Board card. Draggable via dnd-kit (`useDraggable`); the
 * drop is resolved by RoadmapBoardPage's `DndContext`. Expanding lazily
 * fetches the Epic's Stories — `hasLoadedStories` stays true once tripped so
 * collapsing/re-expanding reuses the cached `useStories` query instead of
 * re-fetching (only the *visibility* of the story list toggles on `expanded`).
 */
export default function EpicBoardCard({ epic }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [hasLoadedStories, setHasLoadedStories] = useState(false);

  const { data: stories, isLoading: storiesLoading } = useStories(
    hasLoadedStories ? epic.id : undefined
  );

  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: epic.id,
    data: { stage: epic.stage },
  });

  const style = transform
    ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` }
    : undefined;

  function toggleExpand() {
    setExpanded((prev) => !prev);
    if (!hasLoadedStories) setHasLoadedStories(true);
  }

  return (
    <Card
      ref={setNodeRef}
      style={style}
      data-testid="epic-board-card"
      data-epic-id={epic.id}
      className={cn("touch-none select-none", isDragging && "opacity-50")}
      {...listeners}
      {...attributes}
    >
      <CardHeader className="gap-1 p-3 pb-2">
        <div className="flex items-start justify-between gap-2">
          <span
            data-testid="epic-board-card-title"
            className="min-w-0 flex-1 truncate text-sm font-medium"
          >
            {epic.title}
          </span>
          <button
            type="button"
            data-testid="epic-board-card-expand"
            className="shrink-0 rounded p-0.5 text-muted-foreground hover:bg-muted hover:text-foreground"
            aria-expanded={expanded}
            aria-label={expanded ? "Collapse stories" : "Expand stories"}
            onPointerDown={(e) => e.stopPropagation()}
            onClick={(e) => {
              e.stopPropagation();
              toggleExpand();
            }}
          >
            {expanded ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
          </button>
        </div>
        <span data-testid="epic-board-card-progress" className="text-xs text-muted-foreground">
          {epic.progress.doneTasks} of {epic.progress.totalTasks} tasks complete
        </span>
      </CardHeader>

      {expanded && (
        <CardContent
          data-testid="epic-board-card-stories"
          className="flex flex-col gap-1.5 p-3 pt-0"
        >
          {storiesLoading && <Skeleton className="h-4 w-full" />}
          {stories?.map((story) => (
            <div
              key={story.id}
              data-testid="epic-board-card-story"
              className="flex items-center justify-between gap-2 rounded border bg-background/50 px-2 py-1 text-xs"
            >
              <span className="min-w-0 flex-1 truncate">{story.title}</span>
              <span
                data-testid="epic-board-card-story-progress"
                className="shrink-0 text-muted-foreground"
              >
                {story.progress.doneTasks}/{story.progress.totalTasks}
              </span>
            </div>
          ))}
          {stories && stories.length === 0 && (
            <p className="text-xs text-muted-foreground">No stories yet.</p>
          )}
        </CardContent>
      )}
    </Card>
  );
}
