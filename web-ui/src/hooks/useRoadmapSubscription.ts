import { useQueryClient } from "@tanstack/react-query";
import type { RoadmapItemEvent } from "@/lib/types";
import { showEventToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import { useStompSubscription } from "./useStompSubscription";
import { useResolveFeedTopic } from "@/FeedTopicContext";

export function useRoadmapSubscription() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  const resolveFeedTopic = useResolveFeedTopic();

  useStompSubscription(resolveFeedTopic("roadmap-items"), (message) => {
    queryClient.invalidateQueries({ queryKey: ["epics"] });
    queryClient.invalidateQueries({ queryKey: ["stories"] });
    queryClient.invalidateQueries({ queryKey: ["tasks"] });

    try {
      const event: RoadmapItemEvent = JSON.parse(message.body);
      const entry = showEventToast(event);
      if (entry) addEntry(entry);
    } catch {
      // Ignore parse errors — still invalidated the query
    }
  });
}
