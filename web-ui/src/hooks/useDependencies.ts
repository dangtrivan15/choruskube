import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { CreateDependencyRequest, DependencyEdgeResponse } from "@/lib/types";

/**
 * Create a "blocking" dependency edge (Roadmap Graph View). `epicId` scopes
 * which graph query to invalidate on success — the caller is always viewing
 * a single Epic's graph when creating an edge from the UI.
 */
export function useCreateDependency(epicId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: CreateDependencyRequest) =>
      api.post<DependencyEdgeResponse>("/dependencies", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics", epicId, "graph"] });
      addEntry(showMutationToast("Dependency created", "success"));
    },
    onError: (error) => {
      // A 409 here is DependencyCycleException — the backend's message names the
      // specific blocking/blocked pair that would close the cycle, which is far
      // more actionable than a generic failure toast (mirrors useStartRun's
      // handling of its own plain-string 400 body).
      if (error instanceof ApiError && error.status === 409 && typeof error.body === "string") {
        addEntry(showMutationToast(error.body, "warning"));
      } else {
        addEntry(showMutationToast("Failed to create dependency", "error"));
      }
    },
  });
}

/** Delete a "blocking" dependency edge (Roadmap Graph View). */
export function useDeleteDependency(epicId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/dependencies/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics", epicId, "graph"] });
      addEntry(showMutationToast("Dependency deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete dependency", "error"));
    },
  });
}
