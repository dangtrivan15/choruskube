import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { PageResponse, PaginationParams, RunSummary } from "@/lib/types";

/**
 * Full run history for a Task (Decision 1 — every run a Task ever launched
 * remains queryable via `task_id`, not just the latest one). Backed by
 * GET /api/v1/tasks/{id}/runs.
 */
export function useTaskRuns(taskId: string | undefined, pagination?: PaginationParams) {
  return useQuery({
    queryKey: ["tasks", taskId, "runs", pagination],
    queryFn: () => api.getPage<PageResponse<RunSummary>>(`/tasks/${taskId}/runs`, pagination),
    enabled: !!taskId,
    refetchInterval: 10_000,
    placeholderData: (prev) => prev,
  });
}
