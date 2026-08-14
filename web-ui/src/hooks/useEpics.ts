import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  EpicResponse,
  EpicRequest,
  EpicUpdateRequest,
  EpicStageUpdateRequest,
  EpicPriorityUpdateRequest,
  EpicTargetDateUpdateRequest,
  PageResponse,
  PaginationParams,
  Priority,
} from "@/lib/types";

/**
 * Pagination the Roadmap Board (RoadmapBoardPage) fetches all Epics with —
 * one page large enough to hold every Epic so the board can group client-side
 * by `stage` without its own pagination UI. `useUpdateEpicStage`'s optimistic
 * update targets this exact query key (see its `boardEpicsQueryKey` below);
 * keep the two in sync if the board's fetch params ever change.
 */
export const EPIC_BOARD_PAGINATION: PaginationParams = { page: 0, size: 200 };

// `readyOnly` takes a real-boolean default (`= false`), not a bare `readyOnly?: boolean`.
// TanStack Query's default key hasher runs `JSON.stringify` on the query key, which drops
// object properties whose value is `undefined` — so an optional-with-no-default parameter
// would let an omitted call bind to `undefined` and hash to a key with no `readyOnly`
// property at all, which would never match `useEpics`'s own key (always an explicit
// boolean, see below). The default ensures every call, explicit or omitted, resolves to a
// real boolean before the key is built, so `useUpdateEpicStage`'s optimistic update always
// targets the correct (possibly-filtered) board cache entry.
function boardEpicsQueryKey(readyOnly: boolean = false) {
  return ["epics", { title: undefined, pagination: EPIC_BOARD_PAGINATION, readyOnly }] as const;
}

export function useEpics(
  title?: string,
  pagination?: PaginationParams,
  readyOnly: boolean = false,
  priority?: Priority
) {
  const params: string[] = [];
  if (title) params.push(`title=${encodeURIComponent(title)}`);
  if (readyOnly) params.push("readiness=READY");
  // Raw value pass-through — mirrors how `useAllStories` threads its `stage`
  // param, NOT the boolean "READY only" toggle `readyOnly` uses above. Omitting
  // it (undefined) is dropped from the query key by TanStack's JSON.stringify
  // hasher, so a caller that never filters by priority (e.g. the Board's
  // `boardEpicsQueryKey`) still binds to the same cache entry it did before.
  if (priority) params.push(`priority=${encodeURIComponent(priority)}`);
  const queryString = params.length > 0 ? `?${params.join("&")}` : "";

  return useQuery({
    queryKey: ["epics", { title, pagination, readyOnly, priority }],
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
    // EpicUpdateRequest (not EpicRequest): the full PUT edit carries no
    // `priority` — that moves via `useUpdateEpicPriority` (PATCH) only, mirroring
    // how `stage` is edit-immutable on this path and moved via `useUpdateEpicStage`.
    mutationFn: ({ id, body }: { id: string; body: EpicUpdateRequest }) =>
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
 * Re-prioritize an Epic via `PATCH /epics/{id}/priority`, independent of the
 * content-edit guard on the full PUT edit endpoint — the priority equivalent of
 * `useUpdateEpicStage`. Invalidates the `["epics"]` query key on success (same
 * pattern as `useUpdateEpic`) so every Epic list/detail view re-fetches the new
 * priority; no optimistic update, since the inline detail-page selector is not
 * a latency-sensitive drag interaction.
 */
export function useUpdateEpicPriority() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, priority }: { id: string } & EpicPriorityUpdateRequest) =>
      api.patch<EpicResponse>(`/epics/${id}/priority`, {
        priority,
      } satisfies EpicPriorityUpdateRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Epic priority updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update epic priority", "error"));
    },
  });
}

/**
 * Set or clear (via `null`) an Epic's target date via `PATCH /epics/{id}/target-date`,
 * independent of the content-edit guard on the full PUT edit endpoint — the target-date
 * equivalent of `useUpdateEpicPriority`. Invalidates the `["epics"]` query key on success so
 * every Epic list/detail view re-fetches the new date; no optimistic update, mirroring
 * `useUpdateEpicPriority`.
 */
export function useUpdateEpicTargetDate() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ id, targetDate }: { id: string } & EpicTargetDateUpdateRequest) =>
      api.patch<EpicResponse>(`/epics/${id}/target-date`, {
        targetDate,
      } satisfies EpicTargetDateUpdateRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      addEntry(showMutationToast("Epic target date updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update epic target date", "error"));
    },
  });
}

/**
 * Move an Epic between roadmap board columns (`stage`), independent of the
 * content-edit guard on the full PUT edit endpoint. Applies an optimistic
 * update to the board's own Epics query so a drag-and-drop move is reflected
 * immediately, then rolls back on error.
 *
 * `readyOnly` is required, not optional: it must reflect the board's current
 * "Ready to start" toggle state (`RoadmapBoardPage`'s own local state) so the
 * optimistic update targets the currently-active board cache entry —
 * `boardEpicsQueryKey(readyOnly)` — rather than always the unfiltered one.
 * Since this hook is re-invoked on every `RoadmapBoardPage` render, its
 * `onMutate`/`onError` closures always see the current `readyOnly` value
 * directly; it does not need to be threaded through `mutate()`'s own
 * arguments.
 */
export function useUpdateEpicStage(readyOnly: boolean) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();

  return useMutation({
    mutationFn: ({ id, stage }: { id: string } & EpicStageUpdateRequest) =>
      api.patch<EpicResponse>(`/epics/${id}/stage`, { stage } satisfies EpicStageUpdateRequest),
    onMutate: async ({ id, stage }) => {
      const queryKey = boardEpicsQueryKey(readyOnly);
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
