import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { GitRepoResponse, PageResponse, PaginationParams } from "@/lib/types";

export function useGitRepos(pagination?: PaginationParams) {
  return useQuery({
    queryKey: ["git-repos", { pagination }],
    queryFn: () =>
      api.getPage<PageResponse<GitRepoResponse>>(
        "/git-repos",
        pagination,
      ),
    placeholderData: (prev) => prev,
  });
}

export function useCreateGitRepo() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: {
      url: string;
      defaultBranch?: string;
      testCommand?: string;
      agentImage?: string;
      secrets?: string;
      enableDocker?: boolean;
    }) => api.post<GitRepoResponse>("/git-repos", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["git-repos"] });
      addEntry(showMutationToast("Git repo created", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to create git repo", "error"));
    },
  });
}

export function useUpdateGitRepo() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({
      id,
      body,
    }: {
      id: string;
      body: {
        url: string;
        defaultBranch?: string;
        testCommand?: string;
        agentImage?: string;
        secrets?: string;
        enableDocker?: boolean;
      };
    }) => api.put<GitRepoResponse>(`/git-repos/${id}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["git-repos"] });
      addEntry(showMutationToast("Git repo updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update git repo", "error"));
    },
  });
}

export function useDeleteGitRepo() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/git-repos/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["git-repos"] });
      addEntry(showMutationToast("Git repo deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete git repo", "error"));
    },
  });
}
