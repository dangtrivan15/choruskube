import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  EpicResponse,
  EpicRequest,
  EpicStageUpdateRequest,
  PageResponse,
  PaginationParams,
} from "@/lib/types";

/**
 * Pagination the Roadmap Board (RoadmapBoardPage) fetches all Epics with —
 * one page large enough to hold every Epic so the board can group client-side
 * by `stage` without its own pagination UI. `useUpdateEpicStage`'s optimistic
 * update targets this exact query key (see its `boardEpicsQueryKey` below);
 * keep the two in sync if the board's fetch params ever change.
 */
export const EPIC_BOARD_PAGINATION: PaginationParams = { page: 0, size: 200 };

function boardEpicsQueryKey() {
  return ["epics", { title: undefined, pagination: EPIC_BOARD_PAGINATION }] as const;
}

export function useEpics(title?: string, pagination?: PaginationParams) {
  const params: string[] = [];
  if (title) params.push(`title=${encodeURIComponent(title)}`);
  const queryString = params.length > 0 ? `?${params.join("&")}` : "";

  return useQuery({
    queryKey: ["epics", { title, pagination }],
    queryFn: () =>
      api.getPage<PageResponse<EpicResponse>>(`/epics${queryString}`, pagination),
    refetchInterval: 15_000,
    placeholderData: (prev) => prev,
  });
}

export function useEpic(id: string | undefined) {
  return useQuery({
    queryKey: ["epics", id],
    queryFn: () => api.get<EpicResponse>(`/epics/${id}`),
    enabled: !!id,
  });
}

export function useCreateEpic() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: EpicRequest) => api.post<EpicResponse>("/epics", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Epic created", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to create epic", "error"));
    },
  });
}

export function useUpdateEpic() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: EpicRequest }) =>
      api.put<EpicResponse>(`/epics/${id}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Epic updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update epic", "error"));
    },
  });
}

/**
 * Move an Epic between roadmap board columns (`stage`), independent of the
 * content-edit guard on the full PUT edit endpoint. Applies an optimistic
 * update to the board's own Epics query so a drag-and-drop move is reflected
 * immediately, then rolls back on error.
 */
export function useUpdateEpicStage() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();

  return useMutation({
    mutationFn: ({ id, stage }: { id: string } & EpicStageUpdateRequest) =>
      api.patch<EpicResponse>(`/epics/${id}/stage`, { stage } satisfies EpicStageUpdateRequest),
    onMutate: async ({ id, stage }) => {
      const queryKey = boardEpicsQueryKey();
      await queryClient.cancelQueries({ queryKey });

      // Snapshot only this epic's previous stage, not the whole page: two epics can be
      // dragged in quick succession (mutation A still in flight when B starts), and if A
      // then fails, rolling back the *entire* previously-fetched page would silently wipe
      // out B's already-applied optimistic move until the next refetch reconciles it.
      // Patching back just this epic's stage keeps each mutation's rollback independent.
      const previousStage = queryClient
        .getQueryData<PageResponse<EpicResponse>>(queryKey)
        ?.content.find((epic) => epic.id === id)?.stage;

      // Functional update (reads the live cache at write time) rather than closing over
      // the snapshot read above: keeps this write independent of anything another
      // in-flight mutation's onMutate/onError may have already applied to the same page,
      // the same way onError's rollback below does.
      queryClient.setQueryData<PageResponse<EpicResponse>>(queryKey, (current) =>
        current
          ? {
              ...current,
              content: current.content.map((epic) =>
                epic.id === id ? { ...epic, stage } : epic
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
        // same epic can be dragged again before this call settles (e.g. backlog -> in_progress
        // still in flight when a second drag moves it to rolled_out): that second mutation's
        // onMutate rewrites the cache on top of this one, so if it's already committed a newer
        // value by the time this call fails, blindly restoring `previousStage` here would erase
        // the newer, already-succeeded move using a stale snapshot from before it ever ran.
        queryClient.setQueryData<PageResponse<EpicResponse>>(queryKey, (current) =>
          current
            ? {
                ...current,
                content: current.content.map((epic) =>
                  epic.id === id && epic.stage === appliedStage
                    ? { ...epic, stage: previousStage }
                    : epic
                ),
              }
            : current
        );
      }
      addEntry(showMutationToast("Failed to move epic", "error"));
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["epics"] });
    },
  });
}

export function useDeleteEpic() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/epics/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Epic deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete epic", "error"));
    },
  });
}
