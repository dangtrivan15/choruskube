import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  StoryResponse,
  StoryRequest,
  StoryStageUpdateRequest,
  StoryPriorityUpdateRequest,
  StoryTargetDateUpdateRequest,
  PageResponse,
  PaginationParams,
  Priority,
} from "@/lib/types";

/**
 * Pagination the Story Board (StoryBoardPage) fetches all Stories with — one
 * page large enough to hold every Story so the board can group client-side by
 * `stage` without its own pagination UI. `useUpdateStoryStage`'s optimistic
 * update targets this exact query key (see its `boardStoriesQueryKey` below);
 * keep the two in sync if the board's fetch params ever change. Mirrors
 * `EPIC_BOARD_PAGINATION`/`TASK_BOARD_PAGINATION`.
 */
export const STORY_BOARD_PAGINATION: PaginationParams = { page: 0, size: 200 };

function boardStoriesQueryKey() {
  return ["stories", { stage: undefined, pagination: STORY_BOARD_PAGINATION }] as const;
}

/**
 * Fetches the paginated `GET /api/v1/stories` listing (all Stories in the
 * org, optionally filtered by `stage`) — distinct from `useStories(epicId)`
 * below, which fetches just one Epic's Stories. Backs the Story Board.
 */
export function useAllStories(
  stage?: "backlog" | "in_progress" | "rolled_out",
  pagination?: PaginationParams,
  priority?: Priority
) {
  const params: string[] = [];
  if (stage) params.push(`stage=${encodeURIComponent(stage)}`);
  // Raw value pass-through, exactly like `stage` above.
  if (priority) params.push(`priority=${encodeURIComponent(priority)}`);
  const queryString = params.length > 0 ? `?${params.join("&")}` : "";

  return useQuery({
    queryKey: ["stories", { stage, pagination, priority }],
    queryFn: () => api.getPage<PageResponse<StoryResponse>>(`/stories${queryString}`, pagination),
    refetchInterval: 15_000,
    placeholderData: (prev) => prev,
  });
}

export function useStories(epicId: string | undefined) {
  return useQuery({
    queryKey: ["epics", epicId, "stories"],
    queryFn: () => api.get<StoryResponse[]>(`/epics/${epicId}/stories`),
    enabled: !!epicId,
    refetchInterval: 15_000,
  });
}

export function useStory(id: string | undefined) {
  return useQuery({
    queryKey: ["stories", id],
    queryFn: () => api.get<StoryResponse>(`/stories/${id}`),
    enabled: !!id,
  });
}

export function useCreateStory(epicId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: StoryRequest) =>
      api.post<StoryResponse>(`/epics/${epicId}/stories`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics", epicId, "stories"] });
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Story created", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to create story", "error"));
    },
  });
}

/**
 * Move a Story between Story Board columns (`stage`), independent of the
 * content-edit guard on the full PUT edit endpoint. Applies an optimistic
 * update to the board's own Stories query so a drag-and-drop move is
 * reflected immediately, then rolls back on error. Mirrors
 * `useUpdateEpicStage` exactly — see that hook's comments for the reasoning
 * behind the per-item (not whole-page) snapshot/rollback.
 */
export function useUpdateStoryStage() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();

  return useMutation({
    mutationFn: ({ id, stage }: { id: string } & StoryStageUpdateRequest) =>
      api.patch<StoryResponse>(`/stories/${id}/stage`, { stage } satisfies StoryStageUpdateRequest),
    onMutate: async ({ id, stage }) => {
      const queryKey = boardStoriesQueryKey();
      await queryClient.cancelQueries({ queryKey });

      // Snapshot only this story's previous stage, not the whole page: two stories can be
      // dragged in quick succession (mutation A still in flight when B starts), and if A
      // then fails, rolling back the *entire* previously-fetched page would silently wipe
      // out B's already-applied optimistic move until the next refetch reconciles it.
      // Patching back just this story's stage keeps each mutation's rollback independent.
      const previousStage = queryClient
        .getQueryData<PageResponse<StoryResponse>>(queryKey)
        ?.content.find((story) => story.id === id)?.stage;

      // Functional update (reads the live cache at write time) rather than closing over
      // the snapshot read above: keeps this write independent of anything another
      // in-flight mutation's onMutate/onError may have already applied to the same page,
      // the same way onError's rollback below does.
      queryClient.setQueryData<PageResponse<StoryResponse>>(queryKey, (current) =>
        current
          ? {
              ...current,
              content: current.content.map((story) =>
                story.id === id ? { ...story, stage } : story
              ),
            }
          : current
      );

      return { previousStage, queryKey, id, appliedStage: stage };
    },
    onError: (_err, _vars, context) => {
      if (context?.previousStage) {
        const { queryKey, id, previousStage, appliedStage } = context;
        // Only roll back if the cache still holds *this* mutation's optimistic write. The
        // same story can be dragged again before this call settles (e.g. backlog -> in_progress
        // still in flight when a second drag moves it to rolled_out): that second mutation's
        // onMutate rewrites the cache on top of this one, so if it's already committed a newer
        // value by the time this call fails, blindly restoring `previousStage` here would erase
        // the newer, already-succeeded move using a stale snapshot from before it ever ran.
        queryClient.setQueryData<PageResponse<StoryResponse>>(queryKey, (current) =>
          current
            ? {
                ...current,
                content: current.content.map((story) =>
                  story.id === id && story.stage === appliedStage
                    ? { ...story, stage: previousStage }
                    : story
                ),
              }
            : current
        );
      }
      addEntry(showMutationToast("Failed to move story", "error"));
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["stories"] });
    },
  });
}

/**
 * Re-prioritize a Story via `PATCH /stories/{id}/priority` — the Story-level
 * mirror of `useUpdateEpicPriority`. Invalidates the `["stories"]` query key on
 * success (same pattern as `useUpdateStoryStage`'s onSettled) so the Story
 * detail page and any Story list re-fetch the new priority.
 */
export function useUpdateStoryPriority() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, priority }: { id: string } & StoryPriorityUpdateRequest) =>
      api.patch<StoryResponse>(`/stories/${id}/priority`, {
        priority,
      } satisfies StoryPriorityUpdateRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["stories"] });
      addEntry(showMutationToast("Story priority updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update story priority", "error"));
    },
  });
}

/**
 * Set or clear (via `null`) a Story's target date via `PATCH /stories/{id}/target-date` — the
 * Story-level mirror of `useUpdateEpicTargetDate`. Invalidates the `["stories"]` query key on
 * success (same pattern as `useUpdateStoryPriority`) so the Story detail page and any Story
 * list re-fetch the new date.
 */
export function useUpdateStoryTargetDate() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, targetDate }: { id: string } & StoryTargetDateUpdateRequest) =>
      api.patch<StoryResponse>(`/stories/${id}/target-date`, {
        targetDate,
      } satisfies StoryTargetDateUpdateRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["stories"] });
      addEntry(showMutationToast("Story target date updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update story target date", "error"));
    },
  });
}

export function useDeleteStory(epicId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/stories/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics", epicId, "stories"] });
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Story deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete story", "error"));
    },
  });
}
