import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { EpicResponse, EpicRequest, PageResponse, PaginationParams } from "@/lib/types";

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
