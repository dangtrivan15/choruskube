import { createContext, useContext } from "react";

/**
 * Focus lives on the `RoadmapTimeline` wrapper — its `onNodeClick` is the only holder of
 * `onFocusChange(epicId, storyId?)` — but the leaf node renderers (`RoadmapTimelineNode`) receive
 * only `data` from `@xyflow/react`, not arbitrary props. This context is how the keyboard
 * (Enter/Space) activation path added on the leaf nodes reaches the exact same focus call a
 * pointer click already makes, without duplicating focus logic down in the leaf (§3.1,
 * Implementation Plan Task 4).
 *
 * Lives in its own module (rather than inline in `RoadmapTimeline.tsx`) so the wrapper and the
 * leaf node file — which already import from each other (`RoadmapTimeline` imports the node
 * components; the node components need this context) — don't form a circular module dependency.
 */
const TimelineFocusContext = createContext<((epicId: string, storyId?: string) => void) | null>(null);

export const TimelineFocusProvider = TimelineFocusContext.Provider;

/**
 * Leaf-node hook for the keyboard-activation callback. Returns `null` outside a `RoadmapTimeline`
 * (e.g. an isolated node unit test) — consumers must tolerate that rather than assume a provider
 * is always present.
 */
export function useTimelineFocusActivate() {
  return useContext(TimelineFocusContext);
}
