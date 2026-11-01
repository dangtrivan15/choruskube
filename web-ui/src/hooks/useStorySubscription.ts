import { useQueryClient } from "@tanstack/react-query";
import type { RoadmapItemEvent } from "@/lib/types";
import { useResolveFeedTopic } from "@/FeedTopicContext";
import { useStompSubscription } from "./useStompSubscription";

/**
 * Refreshes one Story's `["stories", id, ...]` queries on that Story's roadmap events, without the
 * toasts `useRoadmapSubscription` raises for every roadmap change. For pages that read Story state
 * but are not roadmap pages, such as the Run page.
 */
export function useStorySubscription(storyId: string | null | undefined) {
  const queryClient = useQueryClient();
  const resolveFeedTopic = useResolveFeedTopic();

  useStompSubscription(storyId ? resolveFeedTopic("roadmap-items") : null, (message) => {
    let event: RoadmapItemEvent | null = null;
    try {
      event = JSON.parse(message.body);
    } catch {
      // Unreadable: refreshing costs one read, missing the event leaves the page stale.
    }
    if (event && (event.itemType !== "story_changed" || event.itemId !== storyId)) return;
    queryClient.invalidateQueries({ queryKey: ["stories", storyId] });
  });
}
