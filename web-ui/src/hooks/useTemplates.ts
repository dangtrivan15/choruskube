import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { GraphTemplateResponse, PageResponse, PaginationParams } from "@/lib/types";

export function useTemplates(latestOnly = false, name?: string, pagination?: PaginationParams) {
  const params: string[] = [];
  if (latestOnly) params.push("latestOnly=true");
  if (name) params.push(`name=${encodeURIComponent(name)}`);
  const queryString = params.length > 0 ? `?${params.join("&")}` : "";

  return useQuery({
    queryKey: ["templates", { latestOnly, name, pagination }],
    queryFn: () =>
      api.getPage<PageResponse<GraphTemplateResponse>>(
        `/graph-templates${queryString}`,
        pagination,
      ),
    placeholderData: (prev) => prev,
  });
}
