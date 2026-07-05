import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { UsageSummaryResponse } from "@/lib/types";

export function useUsageSummary(orgId: string | undefined) {
  return useQuery({
    queryKey: ["usage", orgId],
    queryFn: () => api.get<UsageSummaryResponse>(`/organizations/${orgId}/usage`),
    enabled: !!orgId,
    staleTime: 30_000,
    refetchInterval: 60_000,
  });
}
