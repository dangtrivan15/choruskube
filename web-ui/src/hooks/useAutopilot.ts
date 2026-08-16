import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import { useStompSubscription } from "./useStompSubscription";
import { useResolveFeedTopic } from "@/FeedTopicContext";
import type { AutopilotStatus, AutopilotUpdateRequest } from "@/lib/types";

const AUTOPILOT_QUERY_KEY = ["autopilot"] as const;

export function useAutopilot() {
  return useQuery({
    queryKey: AUTOPILOT_QUERY_KEY,
    queryFn: () => api.get<AutopilotStatus>("/autopilot"),
    refetchInterval: 15_000,
  });
}

export function useUpdateAutopilot() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: AutopilotUpdateRequest) =>
      api.patch<AutopilotStatus>("/autopilot", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AUTOPILOT_QUERY_KEY });
      addEntry(showMutationToast("Autopilot parallelism updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update Autopilot parallelism", "error"));
    },
  });
}

export function useEngageAutopilot() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: () => api.post<AutopilotStatus>("/autopilot/engage"),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AUTOPILOT_QUERY_KEY });
      addEntry(showMutationToast("Autopilot engaged", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to engage Autopilot", "error"));
    },
  });
}

export function useDisengageAutopilot() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: () => api.post<AutopilotStatus>("/autopilot/disengage"),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AUTOPILOT_QUERY_KEY });
      addEntry(showMutationToast("Autopilot disengaged", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to disengage Autopilot", "error"));
    },
  });
}

export function useTickAutopilot() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: () => api.post<AutopilotStatus>("/autopilot/tick"),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AUTOPILOT_QUERY_KEY });
      addEntry(showMutationToast("Autopilot tick run", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to run Autopilot tick", "error"));
    },
  });
}

/**
 * Live-updates the `["autopilot"]` query from `/topic/autopilot`. Unlike the other feed
 * subscriptions in this file's siblings (which invalidate and let a refetch resolve the new
 * state), this one writes the parsed payload straight into the cache with `setQueryData`.
 * `AutopilotStatusResponse`'s javadoc documents why: the backend publishes the exact same
 * snapshot it just committed on every engage/disengage/update/tick, specifically so a
 * subscriber can render from the event alone instead of a refetch racing the transaction
 * that produced it. A parse failure falls back to `invalidateQueries` so the panel still
 * catches up on the next poll.
 */
export function useAutopilotSubscription() {
  const queryClient = useQueryClient();
  const resolveFeedTopic = useResolveFeedTopic();

  useStompSubscription(resolveFeedTopic("autopilot"), (message) => {
    try {
      const status: AutopilotStatus = JSON.parse(message.body);
      queryClient.setQueryData(AUTOPILOT_QUERY_KEY, status);
    } catch {
      queryClient.invalidateQueries({ queryKey: AUTOPILOT_QUERY_KEY });
    }
  });
}
