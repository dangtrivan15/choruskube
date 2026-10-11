import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { GateStampResponse } from "@/lib/types";

/** Keyed under ["runs", runId] so the run's existing invalidations refresh it too. */
export function useGateStamps(runId: string) {
  return useQuery({
    queryKey: ["runs", runId, "gate-stamps"],
    queryFn: () => api.get<GateStampResponse[]>(`/runs/${runId}/gate-stamps`),
    enabled: !!runId,
  });
}
