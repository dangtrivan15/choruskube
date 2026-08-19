import { useState } from "react";
import { Link } from "react-router";
import { useDraggable } from "@dnd-kit/core";
import { ChevronDown, ChevronRight, CircleCheck } from "lucide-react";
import { useStories } from "@/hooks/useStories";
import type { EpicResponse } from "@/lib/types";
import { Card, CardHeader, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import { roadmapLevelMeta } from "@/lib/roadmapLevel";
import { cn } from "@/lib/utils";

const levelMeta = roadmapLevelMeta("epic");

interface Props {
  epic: EpicResponse;
  /** Whether this card is the currently-focused Epic (§3.1/§3.3) — drives highlight styling. */
  isFocused?: boolean;
  /** Called on card click (excluding the expand chevron, which already stops propagation). */
  onFocus?: (epicId: string) => void;
  /**
   * Whether this card should render pre-expanded on mount, with no click needed — used when the
   * card arrives already focused on one of its Stories (a `story` focus param). Only read as the
   * `expanded`/`hasLoadedStories` state hooks' *lazy initial value*; changing it after mount does
   * not re-expand/re-collapse an already-mounted card (React `useState` ignores a changed initial
   * argument on re-render) — the same "click-only, never auto-cleared" semantics Decision 4 gives
   * focus generally.
   */
  initiallyExpanded?: boolean;
  /** The Story (if any) to highlight once the Story list is visible. */
  focusedStoryId?: string;
  /**
   * Receives the underlying `Card` DOM node so the page can `scrollIntoView` it. Not a plain
   * `ref` prop — this element's `ref` slot is already taken by dnd-kit's `setNodeRef`
   * (`useDraggable`), and a single JSX element can only receive one `ref` value, so the two are
   * merged inline in the `Card` below rather than one clobbering the other.
   */
  cardRef?: (node: HTMLDivElement | null) => void;
}

/**
 * A single Roadmap Board card. Draggable via dnd-kit (`useDraggable`); the
 * drop is resolved by RoadmapBoardPage's `DndContext`. Expanding lazily
 * fetches the Epic's Stories — `hasLoadedStories` stays true once tripped so
 * collapsing/re-expanding reuses the cached `useStories` query instead of
 * re-fetching (only the *visibility* of the story list toggles on `expanded`).
 */
export default function EpicBoardCard({
  epic,
  isFocused = false,
  onFocus,
  initiallyExpanded = false,
  focusedStoryId,
  cardRef,
}: Props) {
  const [expanded, setExpanded] = useState(initiallyExpanded);
  const [hasLoadedStories, setHasLoadedStories] = useState(initiallyExpanded);

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
      ref={(node) => {
        setNodeRef(node);
        cardRef?.(node);
      }}
      style={style}
      data-testid="epic-board-card"
      data-epic-id={epic.id}
      data-focused={isFocused ? "true" : "false"}
      onClick={() => onFocus?.(epic.id)}
      className={cn(
        "touch-none select-none",
        isDragging && "opacity-50",
        isFocused && "ring-2 ring-ring ring-offset-1",
      )}
      {...listeners}
      {...attributes}
    >
      <CardHeader className="gap-1 p-3 pb-2">
        <div className="flex items-start justify-between gap-2">
          <span className="flex min-w-0 flex-1 items-center gap-1.5">
            <levelMeta.Icon className={cn("size-3.5 shrink-0", levelMeta.textClass)} />
            <Link
              to={`/roadmap/epics/${epic.id}`}
              data-testid="epic-board-card-title"
              className="min-w-0 truncate text-sm font-medium hover:underline"
              // Two guards, not the one TaskBoardCard's title needs. `onPointerDown` keeps the
              // press from reaching the card's dnd-kit `listeners` so this reads as a click, not a
              // drag. `onClick` is the extra one: this Card *also* carries a root `onClick` that
              // focuses the Epic, and react-router's `Link` doesn't stop propagation — without it
              // both fire, and `setSearchParams` rewrites the just-navigated location into
              // `/roadmap/epics/:id?epic=:id`. Widths stay hugging (no `flex-1`) so the rest of the
              // row is still grab area for the drag.
              onPointerDown={(e) => e.stopPropagation()}
              onClick={(e) => e.stopPropagation()}
            >
              {epic.title}
            </Link>
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
        <div className="flex flex-wrap items-center gap-2">
          <PriorityBadge priority={epic.priority} size="compact" data-testid="epic-board-card-priority" />
          <span data-testid="epic-board-card-progress" className="text-xs text-muted-foreground">
            {epic.progress.doneTasks} of {epic.progress.totalTasks} tasks complete
          </span>
          {epic.readyItemCount > 0 && (
            <span
              data-testid="epic-board-card-ready-count"
              className="inline-flex items-center gap-1 text-xs text-muted-foreground"
            >
              <CircleCheck className="size-3" />
              {epic.readyItemCount} ready
            </span>
          )}
        </div>
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
              data-story-id={story.id}
              data-focused={story.id === focusedStoryId ? "true" : "false"}
              className={cn(
                "flex items-center justify-between gap-2 rounded border bg-background/50 px-2 py-1 text-xs",
                story.id === focusedStoryId && "ring-2 ring-ring",
              )}
            >
              <span className="min-w-0 flex-1 truncate">{story.title}</span>
              <div className="flex shrink-0 items-center gap-1.5">
                <ReadinessBadge
                  readiness={story.readiness}
                  size="compact"
                  data-testid="epic-board-card-story-blocked"
                />
                <span
                  data-testid="epic-board-card-story-progress"
                  className="text-muted-foreground"
                >
                  {story.progress.doneTasks}/{story.progress.totalTasks}
                </span>
              </div>
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
