import { useState } from "react";
import { Link } from "react-router";
import { X } from "lucide-react";
import Authorized from "@/components/Authorized";
import LevelBadge from "@/components/roadmap/LevelBadge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useEpics, EPIC_BOARD_PAGINATION } from "@/hooks/useEpics";
import {
  useCreateDependency,
  useDeleteDependency,
  useEpicDependencies,
} from "@/hooks/useDependencies";
import type { EpicDependencyResponse, EpicResponse } from "@/lib/types";

type Direction = EpicDependencyResponse["direction"];

interface Side {
  /** This Epic's own role on the edges listed here — see EpicDependencyResponse.direction. */
  direction: Direction;
  heading: string;
  empty: string;
  selectLabel: string;
  listTestId: string;
  selectTestId: string;
  addTestId: string;
}

const SIDES: Side[] = [
  {
    direction: "BLOCKED",
    heading: "Blocked by",
    empty: "Nothing blocks this Epic.",
    selectLabel: "Add an Epic that blocks this one",
    listTestId: "epic-blocked-by-list",
    selectTestId: "epic-blocked-by-select",
    addTestId: "epic-blocked-by-add",
  },
  {
    direction: "BLOCKING",
    heading: "Blocks",
    empty: "This Epic blocks nothing.",
    selectLabel: "Add an Epic this one blocks",
    listTestId: "epic-blocks-list",
    selectTestId: "epic-blocks-select",
    addTestId: "epic-blocks-add",
  },
];

function itemHref(dep: EpicDependencyResponse): string {
  switch (dep.itemType) {
    case "epic":
      return `/roadmap/epics/${dep.itemId}`;
    case "story":
      return `/roadmap/epics/${dep.epicId}/stories/${dep.itemId}`;
    case "task":
      return `/tasks/${dep.itemId}`;
  }
}

function epicOptionLabel(epic: EpicResponse): string {
  return `${epic.title} · ${epic.softwareProject.name}`;
}

/**
 * The Epic's own dependency edges — what blocks it and what it blocks — with
 * add (another Epic) and remove. Edges on its Stories/Tasks are not listed;
 * those belong to the graph view.
 */
export default function EpicDependenciesSection({ epicId }: { epicId: string }) {
  const dependencies = useEpicDependencies(epicId);
  const epicsQuery = useEpics(undefined, EPIC_BOARD_PAGINATION);
  const createDependency = useCreateDependency(epicId);
  const deleteDependency = useDeleteDependency(epicId);

  const otherEpics = (epicsQuery.data?.content ?? []).filter((e) => e.id !== epicId);

  function handleAdd(direction: Direction, otherEpicId: string, onAdded: () => void) {
    const [blockingItemId, blockedItemId] =
      direction === "BLOCKED" ? [otherEpicId, epicId] : [epicId, otherEpicId];
    createDependency.mutate(
      { blockingItemType: "epic", blockingItemId, blockedItemType: "epic", blockedItemId },
      { onSuccess: onAdded },
    );
  }

  return (
    <section data-testid="epic-dependencies-section" className="space-y-3">
      <h3 className="text-sm font-medium text-muted-foreground">Dependencies</h3>
      {dependencies.isLoading ? (
        <Skeleton className="h-16 w-full" />
      ) : dependencies.isError ? (
        <p className="text-sm text-destructive">Failed to load dependencies.</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {SIDES.map((side) => {
            const rows = (dependencies.data ?? []).filter((d) => d.direction === side.direction);
            const linkedEpicIds = new Set(
              rows.filter((d) => d.itemType === "epic").map((d) => d.itemId),
            );
            return (
              <DependencySide
                key={side.direction}
                side={side}
                rows={rows}
                candidates={otherEpics.filter((e) => !linkedEpicIds.has(e.id))}
                adding={createDependency.isPending}
                removing={deleteDependency.isPending}
                onAdd={(otherEpicId, onAdded) => handleAdd(side.direction, otherEpicId, onAdded)}
                onRemove={(dep) =>
                  deleteDependency.mutate({ id: dep.edgeId, otherEpicId: dep.epicId })
                }
              />
            );
          })}
        </div>
      )}
    </section>
  );
}

function DependencySide({
  side,
  rows,
  candidates,
  adding,
  removing,
  onAdd,
  onRemove,
}: {
  side: Side;
  rows: EpicDependencyResponse[];
  candidates: EpicResponse[];
  adding: boolean;
  removing: boolean;
  onAdd: (otherEpicId: string, onAdded: () => void) => void;
  onRemove: (dep: EpicDependencyResponse) => void;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = candidates.find((e) => e.id === selectedId);

  return (
    <div className="min-w-0 space-y-2">
      <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {side.heading}
      </h4>
      <ul data-testid={side.listTestId} className="space-y-1">
        {rows.map((dep) => (
          <li
            key={dep.edgeId}
            data-testid="epic-dependency-row"
            className="flex min-w-0 items-center gap-2 rounded-md border px-2 py-1.5"
          >
            <LevelBadge level={dep.itemType} className="px-1.5 py-0.5" />
            <div className="min-w-0 flex-1">
              <Link
                to={itemHref(dep)}
                title={dep.title}
                className="block truncate text-sm font-medium hover:underline"
              >
                {dep.title}
              </Link>
              {dep.itemType !== "epic" && (
                <span className="block truncate text-xs text-muted-foreground">
                  in {dep.epicTitle}
                </span>
              )}
            </div>
            <Authorized require="canAdmin">
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                aria-label={`Remove dependency on ${dep.title}`}
                data-testid="epic-dependency-remove"
                disabled={removing}
                onClick={() => onRemove(dep)}
              >
                <X />
              </Button>
            </Authorized>
          </li>
        ))}
      </ul>
      {rows.length === 0 && <p className="text-sm text-muted-foreground">{side.empty}</p>}
      <Authorized require="canOperate">
        <div className="flex min-w-0 items-center gap-2">
          <Select
            value={selectedId}
            onValueChange={(v) => setSelectedId(v ?? null)}
            disabled={candidates.length === 0}
          >
            <SelectTrigger
              data-testid={side.selectTestId}
              aria-label={side.selectLabel}
              size="sm"
              className="min-w-0 flex-1"
            >
              <SelectValue
                placeholder={candidates.length === 0 ? "No other Epics" : "Select an Epic…"}
              >
                {selected ? epicOptionLabel(selected) : null}
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              {candidates.map((e) => (
                <SelectItem key={e.id} value={e.id}>
                  {epicOptionLabel(e)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button
            type="button"
            size="sm"
            data-testid={side.addTestId}
            disabled={!selected || adding}
            onClick={() => selected && onAdd(selected.id, () => setSelectedId(null))}
          >
            Add
          </Button>
        </div>
      </Authorized>
    </div>
  );
}
