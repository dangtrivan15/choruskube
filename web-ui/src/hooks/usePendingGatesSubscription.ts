import { useQueryClient } from "@tanstack/react-query";
import type { RunEvent } from "@/lib/types";
import { showEventToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import { useStompSubscription } from "./useStompSubscription";
import { useResolveFeedTopic } from "@/FeedTopicContext";

export function usePendingGatesSubscription() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  const resolveFeedTopic = useResolveFeedTopic();

  useStompSubscription(resolveFeedTopic("pending-gates"), (message) => {
    queryClient.invalidateQueries({ queryKey: ["pending-gates"] });

    try {
      const event: RunEvent = JSON.parse(message.body);
      const entry = showEventToast(event);
      if (entry) addEntry(entry);
    } catch {
      // Ignore parse errors — still invalidated the query
    }
  });
}
