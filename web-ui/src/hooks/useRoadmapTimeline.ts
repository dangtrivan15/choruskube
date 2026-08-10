import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { RoadmapTimelineResponse } from "@/lib/types";

/**
 * Full org roadmap for the Timeline View: every scoped Epic laid out as a lane, with its Stories
 * nested underneath. Backed by GET /api/v1/roadmap/timeline.
 *
 * No `refetchInterval` — like `useRoadmapGraph`, this query relies on `useRoadmapSubscription`'s
 * STOMP-driven invalidation of the `["epics"]` prefix to stay fresh, so the query key's first
 * element must be the literal `"epics"` string for that exact-prefix match to catch it.
 */
export function useRoadmapTimeline() {
  return useQuery({
    queryKey: ["epics", "timeline"],
    queryFn: () => api.get<RoadmapTimelineResponse>("/roadmap/timeline"),
  });
}
