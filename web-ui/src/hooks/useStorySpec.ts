import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, apiErrorMessage, ApiError } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  RunResponse,
  StoryConflictResponse,
  StoryReviseRequest,
  StorySpecResponse,
  StorySpecVersionSummary,
} from "@/lib/types";

// Every key sits under `["stories", id]`. Roadmap pages refresh them through
// useRoadmapSubscription's `["stories"]` invalidation; any other page reading them must mount
// useStorySubscription, or they go stale until remounted.

/** The Story's current approved spec, or `null` when it has none (the endpoint's 404). */
export function useStorySpec(storyId: string | undefined) {
  return useQuery<StorySpecResponse | null>({
    queryKey: ["stories", storyId, "spec"],
    queryFn: async () => {
      try {
        return await api.get<StorySpecResponse>(`/stories/${storyId}/spec`);
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: !!storyId,
  });
}

export function useStorySpecVersions(storyId: string | undefined) {
  return useQuery({
    queryKey: ["stories", storyId, "spec", "versions"],
    queryFn: () => api.get<StorySpecVersionSummary[]>(`/stories/${storyId}/spec/versions`),
    enabled: !!storyId,
  });
}

/** One approved version; disabled until `version` is chosen. */
export function useStorySpecVersion(storyId: string | undefined, version: number | null) {
  return useQuery({
    queryKey: ["stories", storyId, "spec", "versions", version],
    queryFn: () => api.get<StorySpecResponse>(`/stories/${storyId}/spec/versions/${version}`),
    enabled: !!storyId && version != null,
  });
}

/** Conflicts Task runs raised against the Story's spec that no revision has answered yet. */
export function useStoryConflicts(storyId: string | undefined) {
  return useQuery({
    queryKey: ["stories", storyId, "conflicts"],
    queryFn: () => api.get<StoryConflictResponse[]>(`/stories/${storyId}/conflicts`),
    enabled: !!storyId,
  });
}

/**
 * Starts the Story's first Story Design run. The server refuses (409) while the Story has an
 * approved spec or an open design run.
 */
export function useDesignStory() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (storyId: string) => api.post<RunResponse>(`/stories/${storyId}/design`),
    onSuccess: (run, storyId) => {
      queryClient.invalidateQueries({ queryKey: ["stories", storyId] });
      addEntry(showMutationToast("Story design started", "success", `/runs/${run.id}`));
    },
    onError: (error) => {
      addEntry(showMutationToast(apiErrorMessage(error, "Failed to start Story design"), "error"));
    },
  });
}

/** Starts a revision run of the Story's approved spec, steered by the human's guidance. */
export function useReviseStory() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ storyId, guidance }: { storyId: string } & StoryReviseRequest) =>
      api.post<RunResponse>(`/stories/${storyId}/revise`, { guidance } satisfies StoryReviseRequest),
    onSuccess: (run, { storyId }) => {
      queryClient.invalidateQueries({ queryKey: ["stories", storyId] });
      addEntry(showMutationToast("Story revision started", "success", `/runs/${run.id}`));
    },
    onError: (error) => {
      addEntry(showMutationToast(apiErrorMessage(error, "Failed to start Story revision"), "error"));
    },
  });
}
