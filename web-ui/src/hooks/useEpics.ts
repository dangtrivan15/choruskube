import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  EpicResponse,
  EpicRequest,
  EpicStage,
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
    mutationFn: ({ id, stage }: { id: string; stage: EpicStage }) =>
      api.patch<EpicResponse>(`/epics/${id}/stage`, { stage }),
    onMutate: async ({ id, stage }) => {
      const queryKey = boardEpicsQueryKey();
      await queryClient.cancelQueries({ queryKey });

      const previous = queryClient.getQueryData<PageResponse<EpicResponse>>(queryKey);
      if (previous) {
        queryClient.setQueryData<PageResponse<EpicResponse>>(queryKey, {
          ...previous,
          content: previous.content.map((epic) =>
            epic.id === id ? { ...epic, stage } : epic
          ),
        });
      }

      return { previous, queryKey };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) {
        queryClient.setQueryData(context.queryKey, context.previous);
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
