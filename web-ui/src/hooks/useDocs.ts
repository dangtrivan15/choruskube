import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { DocsIndexEntry, DocsPageResponse } from "@/lib/types";

export function useDocsList() {
  return useQuery({
    queryKey: ["docs"],
    queryFn: () => api.get<DocsIndexEntry[]>("/docs"),
    staleTime: 60_000,
  });
}

export function useDocsPage(slug: string) {
  return useQuery({
    queryKey: ["docs", slug],
    queryFn: () => api.get<DocsPageResponse>(`/docs/${slug}`),
    enabled: !!slug,
    staleTime: 60_000,
  });
}
