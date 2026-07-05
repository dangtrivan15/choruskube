import { useQueryClient } from "@tanstack/react-query";
import type { FeatureProposalEvent } from "@/lib/types";
import { showEventToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import { useStompSubscription } from "./useStompSubscription";
import { useResolveFeedTopic } from "@/FeedTopicContext";

export function useFeatureProposalSubscription() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  const resolveFeedTopic = useResolveFeedTopic();

  useStompSubscription(resolveFeedTopic("feature-proposals"), (message) => {
    queryClient.invalidateQueries({ queryKey: ["feature-proposals"] });

    try {
      const event: FeatureProposalEvent = JSON.parse(message.body);
      const entry = showEventToast(event);
      if (entry) addEntry(entry);
    } catch {
      // Ignore parse errors — still invalidated the query
    }
  });
}
