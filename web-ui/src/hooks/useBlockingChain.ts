import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type { BlockableItemType, BlockingChainResponse } from "@/lib/types";

/**
 * Full upstream blocking-chain tree for one Story/Task (blocking-chain
 * feature, Decision 3) — fetched lazily, only when the caller passes
 * `enabled: true` (the detail panel does this only when the open item's
 * readiness is "BLOCKED"), never embedded in the Roadmap Graph View response.
 */
export function useBlockingChain(itemType: BlockableItemType, itemId: string, enabled: boolean) {
  return useQuery({
    queryKey: [itemType === "story" ? "stories" : "tasks", itemId, "blocking-chain"],
    queryFn: () =>
      api.get<BlockingChainResponse>(`/${itemType === "story" ? "stories" : "tasks"}/${itemId}/blocking-chain`),
    enabled,
  });
}
