import type { RoadmapDetailItem } from "@/components/roadmap/RoadmapGraphDetailPanel";

/** The three Roadmap views a focus can be carried between. */
export type RoadmapView = "graph" | "board" | "timeline";

/**
 * The shared "what's focused" vocabulary every Roadmap view and the switcher agree on (§3.1): an
 * Epic id, optionally paired with a Story id nested under it. Both keys are optional so this type
 * can represent "nothing focused" (`{}`) as well as an Epic-only or Epic+Story focus.
 */
export interface RoadmapFocus {
  epicId?: string;
  storyId?: string;
}

/**
 * Reads `epic`/`story` out of a URL's search params. Absent or empty values are simply omitted
 * from the result — this never throws, even for a malformed/garbage query string, since
 * `URLSearchParams.get` just returns `null` for anything it can't find (§6's Negative/security
 * case: an unknown id is handled by the caller treating a present-but-stale id as "not found",
 * not by this function rejecting it).
 */
export function parseFocusParams(searchParams: URLSearchParams): RoadmapFocus {
  const epicId = searchParams.get("epic");
  const storyId = searchParams.get("story");
  return {
    ...(epicId ? { epicId } : {}),
    ...(storyId ? { storyId } : {}),
  };
}

/**
 * Builds the plain object `setSearchParams`/`new URLSearchParams(...)` should be constructed
 * from, omitting a key entirely whenever its value is absent rather than including it as
 * `undefined`. `new URLSearchParams({ epic: "e1", story: undefined })` serializes to the literal
 * string `"epic=e1&story=undefined"` — passing an object with an `undefined` value straight to
 * `setSearchParams` writes that literal string into the URL and into every subsequent
 * `parseFocusParams` read, rather than leaving `story` absent as intended (Implementation Plan
 * task 1). Callers updating their own page's URL (RoadmapBoardPage, RoadmapTimelinePage) pass this
 * straight to `setSearchParams`; `buildFocusedUrl` below reuses it for the same reason.
 */
export function focusToSearchParamsInit(focus: RoadmapFocus): Record<string, string> {
  const init: Record<string, string> = {};
  if (focus.epicId) init.epic = focus.epicId;
  if (focus.storyId) init.story = focus.storyId;
  return init;
}

/**
 * Computes the destination URL for switching to `view` while carrying `focus` along (§3.2).
 * Returns `null` for `"graph"` when there's no focused Epic — Graph is strictly per-Epic, so there
 * is nowhere to send an unfocused Graph click (Decision 3); the switcher uses a `null` result to
 * render a disabled control instead of a link.
 */
export function buildFocusedUrl(view: RoadmapView, focus: RoadmapFocus): string | null {
  if (view === "graph") {
    if (!focus.epicId) return null;
    return `/roadmap/epics/${focus.epicId}/graph`;
  }

  const path = view === "board" ? "/roadmap/board" : "/roadmap/timeline";
  const query = new URLSearchParams(focusToSearchParamsInit(focus)).toString();
  return query ? `${path}?${query}` : path;
}

/**
 * Clamps a Roadmap Graph detail-panel selection down to the Epic/Story granularity Board and
 * Timeline understand (Decision 4): a Task selection maps to its parent Story's id, a Story
 * selection to its own id, and an Epic selection contributes nothing extra (Graph already supplies
 * its own Epic id from the route, so there is nothing further to clamp). `null`/`undefined` —
 * `RoadmapGraphPage`'s default state on initial load and after a pane-click deselect — is not an
 * edge case here; it's handled the same way as an Epic selection, returning `{}` so callers never
 * need their own truthiness guard before calling this.
 */
export function clampFocusToStory(detail: RoadmapDetailItem | null | undefined): RoadmapFocus {
  if (!detail) return {};
  if (detail.itemType === "task") return { storyId: detail.item.storyId };
  if (detail.itemType === "story") return { storyId: detail.item.id };
  return {};
}
