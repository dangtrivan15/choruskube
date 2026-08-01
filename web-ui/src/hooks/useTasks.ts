import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  TaskResponse,
  TaskRequest,
  TaskStatusUpdateRequest,
  PageResponse,
  PaginationParams,
} from "@/lib/types";

/**
 * Pagination the Task Board (TaskBoardPage) fetches all Tasks with — one
 * page large enough to hold every Task so the board can group client-side by
 * `status` without its own pagination UI. `useUpdateTaskStatus`'s optimistic
 * update targets this exact query key (see its `boardTasksQueryKey` below);
 * keep the two in sync if the board's fetch params ever change.
 */
export const TASK_BOARD_PAGINATION: PaginationParams = { page: 0, size: 200 };

function boardTasksQueryKey() {
  return ["tasks", { status: undefined, pagination: TASK_BOARD_PAGINATION }] as const;
}

/**
 * Fetches the paginated `GET /api/v1/tasks` listing (all Tasks in the org,
 * optionally filtered by `status`) — distinct from `useTasks(storyId)` below,
 * which fetches just one Story's Tasks. Backs the Task Board.
 */
export function useAllTasks(
  status?: "backlog" | "in_progress" | "done",
  pagination?: PaginationParams
) {
  const params: string[] = [];
  if (status) params.push(`status=${encodeURIComponent(status)}`);
  const queryString = params.length > 0 ? `?${params.join("&")}` : "";

  return useQuery({
    queryKey: ["tasks", { status, pagination }],
    queryFn: () => api.getPage<PageResponse<TaskResponse>>(`/tasks${queryString}`, pagination),
    refetchInterval: 15_000,
    placeholderData: (prev) => prev,
  });
}

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

/**
 * Move a Task between Task Board columns (`status`), via the existing
 * validated-transition endpoint `PATCH /api/v1/tasks/{id}/status` (already
 * enforcing `backlog->in_progress`, `in_progress->done`, and
 * `in_progress->backlog` server-side — this hook does not duplicate that
 * whitelist). Applies an optimistic update to the board's own Tasks query so
 * a drag-and-drop move is reflected immediately, then rolls back on error.
 * Mirrors `useUpdateEpicStage` exactly — see that hook's comments for the
 * reasoning behind the per-item (not whole-page) snapshot/rollback.
 */
export function useUpdateTaskStatus() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();

  return useMutation({
    mutationFn: ({ id, status }: { id: string; status: "backlog" | "in_progress" | "done" }) =>
      api.patch<TaskResponse>(`/tasks/${id}/status`, { status } satisfies TaskStatusUpdateRequest),
    onMutate: async ({ id, status }) => {
      const queryKey = boardTasksQueryKey();
      await queryClient.cancelQueries({ queryKey });

      // Snapshot only this task's previous status, not the whole page: two tasks can be
      // dragged in quick succession (mutation A still in flight when B starts), and if A
      // then fails, rolling back the *entire* previously-fetched page would silently wipe
      // out B's already-applied optimistic move until the next refetch reconciles it.
      // Patching back just this task's status keeps each mutation's rollback independent.
      const previousStatus = queryClient
        .getQueryData<PageResponse<TaskResponse>>(queryKey)
        ?.content.find((task) => task.id === id)?.status;

      // Functional update (reads the live cache at write time) rather than closing over
      // the snapshot read above: keeps this write independent of anything another
      // in-flight mutation's onMutate/onError may have already applied to the same page,
      // the same way onError's rollback below does.
      queryClient.setQueryData<PageResponse<TaskResponse>>(queryKey, (current) =>
        current
          ? {
              ...current,
              content: current.content.map((task) => (task.id === id ? { ...task, status } : task)),
            }
          : current
      );

      return { previousStatus, queryKey, id, appliedStatus: status };
    },
    onError: (_err, _vars, context) => {
      if (context?.previousStatus) {
        const { queryKey, id, previousStatus, appliedStatus } = context;
        // Only roll back if the cache still holds *this* mutation's optimistic write. The
        // same task can be dragged again before this call settles (e.g. backlog -> in_progress
        // still in flight when a second drag moves it to done): that second mutation's
        // onMutate rewrites the cache on top of this one, so if it's already committed a newer
        // value by the time this call fails, blindly restoring `previousStatus` here would erase
        // the newer, already-succeeded move using a stale snapshot from before it ever ran.
        queryClient.setQueryData<PageResponse<TaskResponse>>(queryKey, (current) =>
          current
            ? {
                ...current,
                content: current.content.map((task) =>
                  task.id === id && task.status === appliedStatus
                    ? { ...task, status: previousStatus }
                    : task
                ),
              }
            : current
        );
      }
      addEntry(showMutationToast("Failed to move task", "error"));
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["tasks"] });
    },
  });
}
