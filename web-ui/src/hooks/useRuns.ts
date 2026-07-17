import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "react-router";
import { api, ApiError } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  RunResponse,
  RunSummary,
  ExecutionLogResponse,
  ReviewHistoryResponse,
  PageResponse,
  PaginationParams,
  QuotaExceededResponse,
  StagingRefsResponse,
  AttachmentRefsResponse,
} from "@/lib/types";

export function useRuns(status?: string, name?: string, pagination?: PaginationParams) {
  const params: string[] = [];
  if (status) params.push(`status=${status}`);
  if (name) params.push(`name=${encodeURIComponent(name)}`);
  const queryString = params.length > 0 ? `?${params.join("&")}` : "";

  return useQuery({
    queryKey: ["runs", status, name, pagination],
    queryFn: () =>
      api.getPage<PageResponse<RunSummary>>(`/runs${queryString}`, pagination),
    refetchInterval: 10_000,
    placeholderData: (prev) => prev,
  });
}

export function useRun(id: string) {
  return useQuery({
    queryKey: ["runs", id],
    queryFn: () => api.get<RunResponse>(`/runs/${id}`),
    refetchInterval: 5_000,
    // Avoid firing `/runs/` when called with an empty id (eg. tasks with no linked run yet).
    enabled: !!id,
  });
}

export function useNodeLogs(runId: string, nodeExecId: string | null, enabled: boolean) {
  return useQuery({
    queryKey: ["runs", runId, "nodes", nodeExecId, "logs"],
    queryFn: () => api.get<ExecutionLogResponse[]>(`/runs/${runId}/nodes/${nodeExecId}/logs`),
    enabled: !!nodeExecId && enabled,
    refetchInterval: enabled ? 3_000 : false,
  });
}

export function useReviewHistory(runId: string, loopGroup: string | null) {
  return useQuery({
    queryKey: ["runs", runId, "review-history", loopGroup],
    queryFn: () => api.get<ReviewHistoryResponse[]>(`/runs/${runId}/review-history?loopGroup=${loopGroup}`),
    enabled: !!loopGroup,
  });
}

export function useStartRun() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: async ({
      graphTemplateId, inputs, name, inputFiles,
    }: {
      graphTemplateId: string; inputs: Record<string, unknown>; name?: string; inputFiles?: File[];
    }) => {
      let inputAttachmentRefs: string | undefined;
      if (inputFiles && inputFiles.length > 0) {
        const form = new FormData();
        inputFiles.forEach((f) => form.append("files", f));
        const result = await api.postForm<StagingRefsResponse>("/attachments/temp", form);
        inputAttachmentRefs = result.stagingRefs;
      }
      return api.post<RunResponse>("/runs", {
        graphTemplateId, inputs, name: name || undefined, inputAttachmentRefs,
      });
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ["runs"] });
      navigate(`/runs/${data.id}`);
      addEntry(showMutationToast("Run started successfully", "success", `/runs/${data.id}`));
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 429) {
        const body = error.body as QuotaExceededResponse | null;
        const message = body?.message ?? "Organization quota exceeded. Wait for resources to free up or request a quota increase.";
        addEntry(showMutationToast(message, "warning"));
      } else if (error instanceof ApiError && error.status === 400 && typeof error.body === "string") {
        // InvalidCredentialException from pre-flight check — body is a plain String
        addEntry(showMutationToast(error.body, "warning"));
      } else {
        addEntry(showMutationToast("Failed to start run", "error"));
      }
    },
  });
}

export function useRenameRun(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (name: string) =>
      api.patch<void>(`/runs/${runId}/name`, { name }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["runs"] });
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      addEntry(showMutationToast("Run renamed", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to rename run", "error"));
    },
  });
}

export function usePauseRun(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: () => api.post(`/runs/${runId}/pause`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      addEntry(showMutationToast("Run paused", "info"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to pause run", "error"));
    },
  });
}

export function useResumeRun(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: () => api.post(`/runs/${runId}/resume`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      addEntry(showMutationToast("Run resumed", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to resume run", "error"));
    },
  });
}

export function useCancelRun(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: () => api.post(`/runs/${runId}/cancel`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      addEntry(showMutationToast("Run cancelled", "warning"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to cancel run", "error"));
    },
  });
}

export function useSignalNode(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: async ({ nodeExecId, decision, feedback, files }: {
      nodeExecId: string; decision: string; feedback: string; files?: File[];
    }) => {
      let attachmentRefs: string | undefined;
      if (files && files.length > 0) {
        const form = new FormData();
        files.forEach((f) => form.append("files", f));
        const result = await api.postForm<AttachmentRefsResponse>(
          `/runs/${runId}/nodes/${nodeExecId}/attachments`, form
        );
        attachmentRefs = result.attachmentRefs;
      }
      return api.post(`/runs/${runId}/nodes/${nodeExecId}/signal`, {
        decision, feedback, attachmentRefs,
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      queryClient.invalidateQueries({ queryKey: ["pending-gates"] });
      addEntry(showMutationToast("Decision submitted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to submit decision", "error"));
    },
  });
}

export function useRetryNode(runId: string) {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (nodeExecId: string) =>
      api.post(`/runs/${runId}/nodes/${nodeExecId}/retry`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["runs", runId] });
      addEntry(showMutationToast("Node retry started", "info"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to retry node", "error"));
    },
  });
}
