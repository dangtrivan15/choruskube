import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { RoadmapGraphSnapshot } from "@/lib/types";

/**
 * Full graph view of an Epic's Story/Task tree plus its "blocking" dependency
 * edges (Roadmap Graph View). Backed by GET /api/v1/epics/{epicId}/graph.
 *
 * No `refetchInterval` — unlike the list hooks (useEpics/useStories/useTasks),
 * this query relies on `useRoadmapSubscription`'s STOMP-driven invalidation of
 * the `["epics"]` prefix to stay fresh, the same way `useEpic`/`useStory` do.
 */
export function useRoadmapGraph(epicId: string | undefined) {
  return useQuery({
    queryKey: ["epics", epicId, "graph"],
    queryFn: () => api.get<RoadmapGraphSnapshot>(`/epics/${epicId}/graph`),
    enabled: !!epicId,
  });
}
