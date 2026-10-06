import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api, ApiError } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  CreateDependencyRequest,
  DependencyEdgeResponse,
  EpicDependencyResponse,
} from "@/lib/types";

/** Edges with the Epic itself as an endpoint (the Epic page's Dependencies section). */
export function useEpicDependencies(epicId: string | undefined) {
  return useQuery({
    queryKey: ["epics", epicId, "dependencies"],
    queryFn: () => api.get<EpicDependencyResponse[]>(`/epics/${epicId}/dependencies`),
    enabled: !!epicId,
  });
}

// An edge appears on the graph and the dependency list of the Epic at each end, so a write must
// refresh both Epics' views or the far side keeps showing the old edge set.
function invalidateEpicViews(queryClient: QueryClient, epicIds: (string | undefined)[]) {
  for (const id of new Set(epicIds)) {
    if (!id) continue;
    queryClient.invalidateQueries({ queryKey: ["epics", id, "graph"] });
    queryClient.invalidateQueries({ queryKey: ["epics", id, "dependencies"] });
  }
}

/**
 * Create a "blocking" dependency edge. `epicId` is the Epic on screen; an Epic
 * named as either endpoint of the request is refreshed as well.
 */
export function useCreateDependency(epicId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: CreateDependencyRequest) =>
      api.post<DependencyEdgeResponse>("/dependencies", body),
    onSuccess: (_edge, body) => {
      invalidateEpicViews(queryClient, [
        epicId,
        body.blockingItemType === "epic" ? body.blockingItemId : undefined,
        body.blockedItemType === "epic" ? body.blockedItemId : undefined,
      ]);
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

export interface DeleteDependencyVariables {
  id: string;
  /** The Epic at the edge's far end (or owning that endpoint), when it is not the hook's `epicId`. */
  otherEpicId?: string;
}

/** Delete a "blocking" dependency edge. */
export function useDeleteDependency(epicId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id }: DeleteDependencyVariables) => api.delete(`/dependencies/${id}`),
    onSuccess: (_data, { otherEpicId }) => {
      invalidateEpicViews(queryClient, [epicId, otherEpicId]);
      addEntry(showMutationToast("Dependency deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete dependency", "error"));
    },
  });
}
