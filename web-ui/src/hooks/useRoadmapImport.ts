import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import { useActivityFeed } from "./useActivityFeed";
import type { RoadmapImportResponse } from "@/lib/types";

export interface ImportRoadmapParams {
  softwareProjectId: string;
  /** The parsed JSON document, sent as-is so the server reports type errors by path. */
  document: Record<string, unknown>;
  dryRun: boolean;
}

/**
 * Validates (`dryRun`) or imports a roadmap document. Errors are left to the caller: a rejected
 * document's `errors[]` is shown in the dialog rather than as a toast.
 */
export function useImportRoadmap() {
  const queryClient = useQueryClient();
  const { addEntry } = useActivityFeed();
  return useMutation({
    mutationFn: ({ softwareProjectId, document, dryRun }: ImportRoadmapParams) => {
      const params = new URLSearchParams({ softwareProjectId, dryRun: String(dryRun) });
      return api.post<RoadmapImportResponse>(`/roadmap/import?${params}`, document);
    },
    onSuccess: (result) => {
      if (result.dryRun) return;
      queryClient.invalidateQueries({ queryKey: ["epics"] });
      queryClient.invalidateQueries({ queryKey: ["milestones"] });
      const count = result.createdEpicIds.length;
      addEntry(showMutationToast(`Imported ${count} ${count === 1 ? "epic" : "epics"}`, "success"));
    },
  });
}
