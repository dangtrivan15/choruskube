import { useQuery, useQueries } from "@tanstack/react-query";
import { api, encodeArtifactPath } from "@/lib/api";
import type { ArtifactEntry, ResolvedArtifactGroup } from "@/lib/types";

export function useArtifacts(runId: string, execId: string) {
  return useQuery({
    queryKey: ["runs", runId, "node-executions", execId, "artifacts"],
    queryFn: () =>
      api.get<ArtifactEntry[]>(`/runs/${runId}/node-executions/${execId}/artifacts`),
  });
}

export function useArtifactContent(
  runId: string,
  execId: string,
  filename: string | null
) {
  return useQuery({
    queryKey: ["runs", runId, "node-executions", execId, "artifacts", filename],
    queryFn: () =>
      api.getText(
        `/runs/${runId}/node-executions/${execId}/artifacts/${encodeArtifactPath(filename!)}`
      ),
    enabled: !!filename,
  });
}

// Caller must pre-filter to groups with non-null nodeExecutionId.
// Results are returned in the same stable index order as the input array.
export function useArtifactsForGroups(
  runId: string,
  groups: ResolvedArtifactGroup[]
) {
  return useQueries({
    queries: groups.map((g) => ({
      queryKey: ["runs", runId, "node-executions", g.nodeExecutionId!, "artifacts"],
      queryFn: () =>
        api.get<ArtifactEntry[]>(`/runs/${runId}/node-executions/${g.nodeExecutionId!}/artifacts`),
    })),
  });
}
