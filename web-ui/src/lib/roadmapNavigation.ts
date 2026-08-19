import type { LucideIcon } from "lucide-react";
import { List, LayoutGrid, GanttChart, Network } from "lucide-react";
import { buildFocusedUrl, type RoadmapFocus, type RoadmapView } from "@/lib/roadmapFocus";
import type { RoadmapLevel } from "@/lib/roadmapLevel";

/**
 * The views that sit on the *view* axis — the interchangeable ways of looking at one ticket type.
 * `"graph"` is excluded by construction: it renders a single Epic's whole mixed-level tree, so it
 * answers "which Epic?", not "which ticket type?", and is offered as a separate contextual action
 * instead (see `roadmapDestination`'s Graph branch).
 */
export type RoadmapViewType = Exclude<RoadmapView, "graph">;

export interface RoadmapViewInfo {
  /** Human-readable label, exactly as it appears on the control. */
  label: string;
  /** View icon. */
  Icon: LucideIcon;
}

/**
 * Per-view label and icon, mirroring `roadmapLevel.ts`'s `roadmapLevelMeta` so a view reads as the
 * sibling concept to a hierarchy level. Graph is included even though it is not a `RoadmapViewType`
 * — the contextual Graph action still needs one canonical label/icon, and having it here is what
 * stops that button from re-inventing its own.
 */
const VIEW_INFO: Record<RoadmapView, RoadmapViewInfo> = {
  list: { label: "List", Icon: List },
  board: { label: "Board", Icon: LayoutGrid },
  timeline: { label: "Timeline", Icon: GanttChart },
  graph: { label: "Open graph", Icon: Network },
};

/**
 * The order the view types are offered in, from the flattest reading of a ticket type to the most
 * spatial. Exported next to `roadmapViewMeta` for the same reason `PRIORITY_ORDER` sits next to
 * `priorityMeta`: `VIEW_INFO` is module-private, so a control rendering one button per view would
 * otherwise have to re-list the values itself.
 */
export const ROADMAP_VIEW_ORDER: readonly RoadmapViewType[] = ["list", "board", "timeline"];

/** Returns the label/icon for a given view. */
export function roadmapViewMeta(view: RoadmapView): RoadmapViewInfo {
  return VIEW_INFO[view];
}

/**
 * Where each *sub-Epic* ticket type's views live. A view absent from a level's record simply has
 * no page for that ticket type — Timeline is absent for both because the timeline endpoint returns
 * Epic lanes and Story markers and no Task data at all, so a Task timeline would render an empty
 * canvas rather than a different view of the same items.
 *
 * Epic-level URLs are deliberately *not* in this table: they're the focus-carrying ones, and
 * `buildFocusedUrl` already owns assembling them so that every page agrees byte-for-byte with the
 * URLs Board and Timeline write back into their own address bar via `setSearchParams`.
 */
const SUB_EPIC_PATHS: Record<Exclude<RoadmapLevel, "epic">, Partial<Record<RoadmapViewType, string>>> = {
  story: { list: "/roadmap/stories", board: "/roadmap/board/stories" },
  task: { list: "/roadmap/tasks", board: "/roadmap/board/tasks" },
};

/**
 * The URL for looking at `level`'s items through `view`, or `null` when that combination has no
 * page — the single source of truth behind every Roadmap header link.
 *
 * Two rules are encoded here, and only here:
 *
 * 1. **Focus is carried to Epic-level destinations and dropped below them.** `/roadmap`,
 *    `/roadmap/board` and `/roadmap/timeline` all speak the `?epic=`/`?story=` vocabulary, so a
 *    focus survives a view switch. The Story and Task pages never read search params, so carrying
 *    a focus across would mint a URL that claims a selection the destination cannot honour or
 *    round-trip.
 * 2. **Graph resolves from the focused Epic, not from the ticket type.** `/roadmap/epics/:id/graph`
 *    needs an Epic id and shows that Epic's Stories and Tasks together, so it is reachable from any
 *    ticket type as long as an Epic is focused, and from none of them when one isn't.
 */
export function roadmapDestination(
  view: RoadmapView,
  level: RoadmapLevel,
  focus: RoadmapFocus = {},
): string | null {
  if (view === "graph") return buildFocusedUrl("graph", focus);
  if (level === "epic") return buildFocusedUrl(view, focus);
  return SUB_EPIC_PATHS[level][view] ?? null;
}

/**
 * The view types that actually exist for `level`, in canonical order — derived from
 * `roadmapDestination` rather than listed separately, so "which buttons do we render" and "where
 * does each button point" can never disagree. A caller renders exactly these and nothing else:
 * a view with no page gets no button, never a disabled one.
 */
export function roadmapViewsForLevel(level: RoadmapLevel): RoadmapViewType[] {
  return ROADMAP_VIEW_ORDER.filter((view) => roadmapDestination(view, level) !== null);
}

/**
 * The view to land on when the ticket type changes from under `view`: the same view where the new
 * ticket type has one, and Board otherwise. Board is the fallback rather than List because it is
 * the only view every ticket type has, and because the switch that needs a fallback (Timeline, an
 * Epic-only view) is spatial — Board keeps the reader in a spatial view rather than dropping them
 * into a flat list. Graph always falls back: it is not a view type, so nothing can preserve it.
 */
export function carryViewToLevel(view: RoadmapView, level: RoadmapLevel): RoadmapViewType {
  if (view !== "graph" && roadmapDestination(view, level) !== null) return view;
  return "board";
}
