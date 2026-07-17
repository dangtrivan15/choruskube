import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { TaskResponse, TaskRequest } from "@/lib/types";

export function useTasks(storyId: string | undefined) {
  return useQuery({
    queryKey: ["stories", storyId, "tasks"],
    queryFn: () => api.get<TaskResponse[]>(`/stories/${storyId}/tasks`),
    enabled: !!storyId,
    refetchInterval: 15_000,
  });
}

export function useTask(id: string | undefined) {
  return useQuery({
    queryKey: ["tasks", id],
    queryFn: () => api.get<TaskResponse>(`/tasks/${id}`),
    enabled: !!id,
    refetchInterval: 5_000,
  });
}

export function useCreateTask(storyId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: TaskRequest) =>
      api.post<TaskResponse>(`/stories/${storyId}/tasks`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["stories", storyId, "tasks"] });
      queryClient.invalidateQueries({ queryKey: ["stories"] });
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Task created", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to create task", "error"));
    },
  });
}

export function useDeleteTask(storyId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/tasks/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["stories", storyId, "tasks"] });
      queryClient.invalidateQueries({ queryKey: ["stories"] });
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Task deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete task", "error"));
    },
  });
}

export function useStartTask() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.post<TaskResponse>(`/tasks/${id}/start`),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["tasks", data.id] });
      queryClient.invalidateQueries({ queryKey: ["tasks", data.id, "runs"] });
      queryClient.invalidateQueries({ queryKey: ["stories"] });
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Task workflow started", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to start task", "error"));
    },
  });
}

export function useCompleteTask() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.patch<TaskResponse>(`/tasks/${id}/complete`),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["tasks", data.id] });
      queryClient.invalidateQueries({ queryKey: ["stories"] });
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Task completed", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to complete task", "error"));
    },
  });
}
