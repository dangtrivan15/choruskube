import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type {
  PendingGateResponse,
  PendingGateCountResponse,
  PageResponse,
  PaginationParams,
  AttachmentRefsResponse,
  RoadmapCandidatesDocument,
} from "@/lib/types";

export function usePendingGates(pagination?: PaginationParams) {
  return useQuery({
    queryKey: ["pending-gates", pagination],
    queryFn: () =>
      api.getPage<PageResponse<PendingGateResponse>>("/pending-gates", pagination),
    refetchInterval: 15_000,
    placeholderData: (prev) => prev,
  });
}

export function usePendingGatesCount() {
  return useQuery({
    queryKey: ["pending-gates", "count"],
    queryFn: () => api.get<PendingGateCountResponse>("/pending-gates/count"),
    refetchInterval: 15_000,
  });
}

export function useSignalFromDashboard() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: async ({
      runId,
      nodeExecId,
      decision,
      feedback,
      files,
      editedCandidates,
    }: {
      runId: string;
      nodeExecId: string;
      decision: string;
      feedback: string;
      files?: File[];
      editedCandidates?: RoadmapCandidatesDocument;
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
        decision,
        feedback,
        attachmentRefs,
        ...(editedCandidates !== undefined ? { editedCandidates } : {}),
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["pending-gates"] });
      addEntry(showMutationToast("Decision submitted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to submit decision", "error"));
    },
  });
}
