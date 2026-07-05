import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { FeatureProposalResponse, PageResponse, PaginationParams } from "@/lib/types";

export function useFeatureProposals(status?: string, title?: string, pagination?: PaginationParams) {
  const params: string[] = [];
  if (status) params.push(`status=${status}`);
  if (title) params.push(`title=${encodeURIComponent(title)}`);
  const queryString = params.length > 0 ? `?${params.join("&")}` : "";

  return useQuery({
    queryKey: ["feature-proposals", { status, title, pagination }],
    queryFn: () =>
      api.getPage<PageResponse<FeatureProposalResponse>>(
        `/feature-proposals${queryString}`,
        pagination,
      ),
    refetchInterval: 15_000,
    placeholderData: (prev) => prev,
  });
}

export function useFeatureProposal(id: string | undefined) {
  return useQuery({
    queryKey: ["feature-proposals", id],
    queryFn: () => api.get<FeatureProposalResponse>(`/feature-proposals/${id}`),
    enabled: !!id,
  });
}

export function useCreateFeatureProposal() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (body: {
      title: string;
      description: string;
      motivation: string | null;
      softwareProjectId: string;
    }) => api.post<FeatureProposalResponse>("/feature-proposals", body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["feature-proposals"] });
      addEntry(showMutationToast("Proposal created", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to create proposal", "error"));
    },
  });
}

export function useUpdateFeatureProposal() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({
      id,
      body,
    }: {
      id: string;
      body: {
        title: string;
        description: string;
        motivation: string | null;
        softwareProjectId: string;
      };
    }) => api.put<FeatureProposalResponse>(`/feature-proposals/${id}`, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["feature-proposals"] });
      addEntry(showMutationToast("Proposal updated", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to update proposal", "error"));
    },
  });
}

export function useDeleteFeatureProposal() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) => api.delete(`/feature-proposals/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["feature-proposals"] });
      addEntry(showMutationToast("Proposal deleted", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to delete proposal", "error"));
    },
  });
}

export function useStartFeatureProposal() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) =>
      api.post<FeatureProposalResponse>(`/feature-proposals/${id}/start`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["feature-proposals"] });
      addEntry(showMutationToast("Proposal workflow started", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to start proposal", "error"));
    },
  });
}

export function useRollOutFeatureProposal() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: (id: string) =>
      api.patch<FeatureProposalResponse>(`/feature-proposals/${id}/roll-out`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["feature-proposals"] });
      addEntry(showMutationToast("Proposal rolled out", "success"));
    },
    onError: () => {
      addEntry(showMutationToast("Failed to roll out proposal", "error"));
    },
  });
}
