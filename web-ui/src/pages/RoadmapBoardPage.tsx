import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
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
import { parseFocusParams, focusToSearchParamsInit } from "@/lib/roadmapFocus";
import { Skeleton } from "@/components/ui/skeleton";
import PageHeader from "@/components/layout/PageHeader";
import BoardColumn from "@/components/roadmap/BoardColumn";
import RoadmapReadyToggle from "@/components/roadmap/RoadmapReadyToggle";
import RoadmapViewControls from "@/components/roadmap/RoadmapViewControls";
import { Separator } from "@/components/ui/separator";

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
  const [readyOnly, setReadyOnly] = useState(false);
  const { data: pageData, isLoading } = useEpics(undefined, EPIC_BOARD_PAGINATION, readyOnly);
  const updateStage = useUpdateEpicStage(readyOnly);
  const [searchParams, setSearchParams] = useSearchParams();
  useRoadmapSubscription();

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor)
  );

  const epics = pageData?.content;

  const rawFocus = parseFocusParams(searchParams);
  // An `epic` id that doesn't match any loaded Epic (deleted, garbage, or an id from a different
  // org —'s Negative/security case) is treated as "nothing focused", all the way out to the
  // switcher — not just "no card highlighted". A `story` id is left for EpicBoardCard to resolve
  // once its own (lazily-fetched) Stories are in: Board never loads every card's Stories up front,
  // so it has nothing to validate a `story` id against without that pass otherwise-eager fetch.
  const focusedEpicId = epics?.some((e) => e.id === rawFocus.epicId) ? rawFocus.epicId : undefined;
  const focusedStoryId = focusedEpicId ? rawFocus.storyId : undefined;

  const cardNodesRef = useRef<Map<string, HTMLDivElement>>(new Map());
  const registerCardRef = useCallback(
    (epicId: string) => (node: HTMLDivElement | null) => {
      if (node) {
        cardNodesRef.current.set(epicId, node);
      } else {
        cardNodesRef.current.delete(epicId);
      }
    },
    []
  );

  const handleFocusEpic = useCallback(
    (epicId: string) => {
      // history replace, not push  — ordinary card browsing shouldn't balloon the
      // back-button history the way a `push` on every click would.
      setSearchParams(focusToSearchParamsInit({ epicId }), { replace: true });
    },
    [setSearchParams]
  );

  useEffect(() => {
    if (!focusedEpicId) return;
    const node = cardNodesRef.current.get(focusedEpicId);
    node?.scrollIntoView?.({ behavior: "smooth", block: "nearest" });
    // Re-run once the Epics list (re)loads too, not just when focusedEpicId itself changes — on
    // first mount with a focus already in the URL, the focused card's ref only exists once `epics`
    // has actually rendered it.
  }, [focusedEpicId, epics]);

  const byStage = useMemo(() => {
    const groups: Record<EpicStage, EpicResponse[]> = {
      backlog: [],
      in_progress: [],
      rolled_out: [],
    };
    const knownStages = new Set(COLUMNS.map((c) => c.stage));
    for (const epic of epics ?? []) {
      // `stage` is a Postgres enum extended via `ALTER TYPE ... ADD VALUE` (see CLAUDE.md); a
      // value the backend has already shipped but this build predates (e.g. mid rolling-deploy)
      // must not crash the whole app — skip it from the board rather than indexing `groups` with
      // an unrecognized key.
      if (!knownStages.has(epic.stage)) {
        console.warn(`Roadmap Board: skipping epic ${epic.id} with unrecognized stage "${epic.stage}"`);
        continue;
      }
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
        <RoadmapViewControls
          level="epic"
          view="board"
          focusedEpicId={focusedEpicId}
          focusedStoryId={focusedStoryId}
        />
        <Separator orientation="vertical" className="h-6 w-px" />
        <RoadmapReadyToggle checked={readyOnly} onChange={setReadyOnly} />
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
              <BoardColumn
                key={c.stage}
                stage={c.stage}
                label={c.label}
                epics={byStage[c.stage]}
                readyOnly={readyOnly}
                focusedEpicId={focusedEpicId}
                focusedStoryId={focusedStoryId}
                onFocusEpic={handleFocusEpic}
                cardRef={registerCardRef}
              />
            ))}
          </div>
        </DndContext>
      )}
    </div>
  );
}
