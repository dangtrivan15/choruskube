import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { RepoGroup, RepoGroupRequest } from "@/lib/types";

/** Lists every RepoGroup in the active org. Backed by GET /api/v1/repo-groups. */
export function useRepoGroups() {
  return useQuery({
    queryKey: ["repo-groups"],
    queryFn: () => api.get<RepoGroup[]>("/repo-groups"),
  });
}

/**
 * Loads a single RepoGroup with its members. Disabled until {@code id} is
 * defined so route-driven callers can pass {@code params.id} without guards.
 */
export function useRepoGroup(id: string | undefined) {
  return useQuery({
    queryKey: ["repo-groups", id],
    queryFn: () => api.get<RepoGroup>(`/repo-groups/${id}`),
    enabled: !!id,
  });
}

export function useCreateRepoGroup() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: RepoGroupRequest) => api.post<RepoGroup>("/repo-groups", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["repo-groups"] });
      queryClient.invalidateQueries({ queryKey: ["software-projects"] });
      addEntry(showMutationToast("Repo group created", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to create repo group", "error"));
    },
  });
}

export function useUpdateRepoGroup() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: RepoGroupRequest }) =>
      api.put<RepoGroup>(`/repo-groups/${id}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["repo-groups"] });
      queryClient.invalidateQueries({ queryKey: ["software-projects"] });
      addEntry(showMutationToast("Repo group updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update repo group", "error"));
    },
  });
}

/**
 * Replaces the full member list of a RepoGroup. Position is implied by array
 * order (server enforces NotEmpty). Mirrors PUT /repo-groups/{id}/members.
 */
export function useReplaceRepoGroupMembers() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, memberRepoIds }: { id: string; memberRepoIds: string[] }) =>
      api.put<RepoGroup>(`/repo-groups/${id}/members`, { memberRepoIds }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["repo-groups"] });
      queryClient.invalidateQueries({ queryKey: ["software-projects"] });
      addEntry(showMutationToast("Repo group members updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update repo group members", "error"));
    },
  });
}

export function useDeleteRepoGroup() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/repo-groups/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["repo-groups"] });
      queryClient.invalidateQueries({ queryKey: ["software-projects"] });
      addEntry(showMutationToast("Repo group deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete repo group", "error"));
    },
  });
}
