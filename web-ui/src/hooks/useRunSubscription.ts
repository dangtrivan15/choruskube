import { useQueryClient } from "@tanstack/react-query";
import type { RunEvent } from "@/lib/types";
import { showEventToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import { useStompSubscription } from "./useStompSubscription";

export function useRunSubscription(runId: string | undefined) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();

  useStompSubscription(runId ? `/topic/runs/${runId}` : null, (message) => {
    queryClient.invalidateQueries({ queryKey: ["runs", runId] });

    try {
      const event: RunEvent = JSON.parse(message.body);

      const entry = showEventToast(event);
      if (entry) addEntry(entry);
    } catch {
      // Ignore parse errors — still invalidated the query
    }
  });
}
