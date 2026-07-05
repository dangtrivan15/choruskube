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

      // Clear live-chat cache when session completes/fails externally
      if (
        event.type === "live_chat_status_changed" &&
        (event.status === "completed" || event.status === "failed") &&
        event.nodeExecutionId
      ) {
        queryClient.invalidateQueries({
          queryKey: ["live-chat", runId, event.nodeExecutionId],
        });
      }

      const entry = showEventToast(event);
      if (entry) addEntry(entry);
    } catch {
      // Ignore parse errors — still invalidated the query
    }
  });
}
