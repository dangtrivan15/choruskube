import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { StoryResponse, StoryRequest } from "@/lib/types";

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
