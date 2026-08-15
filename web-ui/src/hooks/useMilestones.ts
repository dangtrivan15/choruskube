import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  MilestoneResponse,
  MilestoneRequest,
  MilestoneUpdateRequest,
  EpicResponse,
  EpicMilestoneUpdateRequest,
  PageResponse,
} from "@/lib/types";

/**
 * A large-enough single page to act as "every Milestone" for the callers that need a flat list —
 * the project-scoped `MilestoneSelect` dropdown and the `MilestonesPage` management surface.
 * Mirrors `useGitRepos`/`useRepoGroups`'s "fetch one big page, no UI pagination" treatment for a
 * collection that's small per software project (Caveat 4 of the "Group Epics under a named
 * Milestone / Release" feature: no user-defined ordering, so a plain large page needs no
 * additional client-side sort beyond what the server already applies).
 */
const MILESTONE_LIST_PAGINATION = { size: 100 };

/**
 * Lists Milestones, optionally scoped to a single software project (the `softwareProjectId`
 * query filter `MilestoneController#list` supports). Omitting it lists every Milestone in the
 * active org — used by `MilestonesPage`'s management table.
 */
export function useMilestones(softwareProjectId?: string) {
  const query = softwareProjectId
    ? `?softwareProjectId=${encodeURIComponent(softwareProjectId)}`
    : "";
  return useQuery({
    queryKey: ["milestones", { softwareProjectId }],
    queryFn: () =>
      api.getPage<PageResponse<MilestoneResponse>>(`/milestones${query}`, MILESTONE_LIST_PAGINATION),
    placeholderData: (prev) => prev,
  });
}

/** Loads a single Milestone. Disabled until {@code id} is defined. */
export function useMilestone(id: string | undefined) {
  return useQuery({
    queryKey: ["milestones", id],
    queryFn: () => api.get<MilestoneResponse>(`/milestones/${id}`),
    enabled: !!id,
  });
}

export function useCreateMilestone() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: MilestoneRequest) => api.post<MilestoneResponse>("/milestones", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["milestones"] });
      addEntry(showMutationToast("Milestone created", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to create milestone", "error"));
    },
  });
}

export function useUpdateMilestone() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: MilestoneUpdateRequest }) =>
      api.put<MilestoneResponse>(`/milestones/${id}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["milestones"] });
      // A rename changes the MilestoneRef.name embedded on every tagged Epic's cached
      // EpicResponse — refetch so the Roadmap list/detail badge picks it up immediately
      // (Caveat 5: this covers the mutating client's own session; other sessions catch up
      // on their next short-interval Epic-list refetch).
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Milestone updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update milestone", "error"));
    },
  });
}

export function useDeleteMilestone() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/milestones/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["milestones"] });
      // Deleting a Milestone un-tags its Epics server-side (ON DELETE SET NULL, Decision 2) —
      // refetch so those Epics' cached `milestone` field clears to null.
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Milestone deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete milestone", "error"));
    },
  });
}

/**
 * Assign or clear (via {@code null}) an Epic's Milestone via {@code PATCH /epics/{id}/milestone}
 * (Decision 4). Invalidates both {@code ["epics"]} (the Epic's own `milestone` field changed) and
 * {@code ["milestones"]} (a Milestone's `epicCount` rollup changed) on success.
 */
export function useAssignEpicMilestone() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, milestoneId }: { id: string } & EpicMilestoneUpdateRequest) =>
      api.patch<EpicResponse>(`/epics/${id}/milestone`, {
        milestoneId,
      } satisfies EpicMilestoneUpdateRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      queryClient.invalidateQueries({ queryKey: ["milestones"] });
      addEntry(showMutationToast("Epic milestone updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update epic milestone", "error"));
    },
  });
}
