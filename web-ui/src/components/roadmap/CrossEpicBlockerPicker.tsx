import { useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useEpics, EPIC_BOARD_PAGINATION } from "@/hooks/useEpics";
import { useRoadmapGraph } from "@/hooks/useRoadmapGraph";
import { useCreateDependency } from "@/hooks/useDependencies";
import type { BlockableItemType } from "@/lib/types";

interface Props {
  blockedItemType: BlockableItemType;
  blockedItemId: string;
  /** The Epic whose graph is open — excluded from the target list, and the graph query invalidated on create. */
  currentEpicId: string;
}

/**
 * Adds a blocker that lives in a *different* Epic than the one on screen. The
 * in-panel picker only knows this Epic's items, so cross-Epic needs its own
 * two-step control: pick a target Epic (whose graph is then fetched) → pick one
 * of its items. The created edge surfaces back here as an external "ghost" node.
 */
export default function CrossEpicBlockerPicker({
  blockedItemType,
  blockedItemId,
  currentEpicId,
}: Props) {
  const [targetEpicId, setTargetEpicId] = useState("");
  const [blockerId, setBlockerId] = useState("");
  const epicsQuery = useEpics(undefined, EPIC_BOARD_PAGINATION);
  // Empty targetEpicId disables the query (useRoadmapGraph's `enabled: !!epicId`).
  const targetGraph = useRoadmapGraph(targetEpicId || undefined);
  const createDependency = useCreateDependency(currentEpicId);

  const targetEpics = (epicsQuery.data?.content ?? []).filter((e) => e.id !== currentEpicId);

  const blockerOptions = targetGraph.data
    ? [
        { id: targetGraph.data.epic.id, itemType: "epic" as const, title: targetGraph.data.epic.title },
        ...targetGraph.data.stories.map((s) => ({ id: s.id, itemType: "story" as const, title: s.title })),
        ...targetGraph.data.tasks.map((t) => ({ id: t.id, itemType: "task" as const, title: t.title })),
      ].filter((i) => i.id !== blockedItemId)
    : [];

  function handleAdd() {
    const target = blockerOptions.find((i) => i.id === blockerId);
    if (!target) return;
    createDependency.mutate(
      {
        blockingItemType: target.itemType,
        blockingItemId: target.id,
        blockedItemType,
        blockedItemId,
      },
      {
        onSuccess: () => {
          setBlockerId("");
          setTargetEpicId("");
        },
      },
    );
  }

  return (
    <div data-testid="roadmap-cross-epic-picker" className="space-y-2 pt-3 border-t">
      <h3 className="text-sm font-medium text-muted-foreground">Blocked by an item in another Epic</h3>
      <div className="flex flex-wrap items-center gap-2">
        <Select
          value={targetEpicId}
          onValueChange={(v) => {
            setTargetEpicId(v ?? "");
            setBlockerId("");
          }}
        >
          <SelectTrigger
            data-testid="roadmap-cross-epic-target-select"
            aria-label="Select an Epic"
            size="sm"
            className="w-auto"
          >
            <SelectValue placeholder="Select an Epic…" />
          </SelectTrigger>
          <SelectContent>
            {targetEpics.map((e) => (
              <SelectItem key={e.id} value={e.id}>
                {e.title}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        {targetEpicId && (
          <Select value={blockerId} onValueChange={(v) => setBlockerId(v ?? "")}>
            <SelectTrigger
              data-testid="roadmap-cross-epic-item-select"
              aria-label="Select a blocker"
              size="sm"
              className="w-auto"
            >
              <SelectValue placeholder={targetGraph.isLoading ? "Loading…" : "Select an item…"} />
            </SelectTrigger>
            <SelectContent>
              {blockerOptions.map((opt) => (
                <SelectItem key={opt.id} value={opt.id}>
                  {opt.title} ({opt.itemType})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}

        <Button
          type="button"
          size="sm"
          data-testid="roadmap-cross-epic-submit"
          disabled={!blockerId || createDependency.isPending}
          onClick={handleAdd}
        >
          Add blocker
        </Button>
      </div>
    </div>
  );
}
