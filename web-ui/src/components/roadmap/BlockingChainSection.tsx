import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import type { BlockingChainNode, BlockingChainResponse } from "@/lib/types";

interface Props {
  chain: BlockingChainResponse | undefined;
  isLoading: boolean;
}

/**
 * Status pill for one blocking-chain node. `done` nodes still appear in the
 * tree (something further upstream on the same branch isn't done), so they
 * need a visually muted treatment distinct from the not-done nodes that are
 * the actual holdup — the latter reuses the same warning tone
 * `readinessBadge` (RoadmapGraphDetailPanel) uses for a BLOCKED item.
 */
function nodeStatusBadge(status: BlockingChainNode["status"]) {
  if (status === "done") {
    return (
      <Badge variant="secondary" data-testid="roadmap-blocking-chain-node-status-done">
        done
      </Badge>
    );
  }
  return (
    <Badge
      variant="outline"
      data-testid="roadmap-blocking-chain-node-status-pending"
      className="border-status-warning/20 bg-status-warning/15 text-status-warning"
    >
      {status.replace("_", " ")}
    </Badge>
  );
}

/**
 * One node in the blocking-chain tree, recursively rendering its own
 * `blockedBy`. Nesting is expressed structurally (a nested `<ul className="pl-4">`
 * per level) rather than via inline styles, so indentation compounds
 * naturally with depth. Nodes with further blockers are wrapped in a
 * `<details>` so the (potentially deep/wide) tree is collapsible per level —
 * this codebase has no existing collapsible primitive to reuse.
 */
function BlockingChainNodeItem({ node }: { node: BlockingChainNode }) {
  const hasChildren = node.blockedBy.length > 0;
  const label = (
    <span className="inline-flex items-center gap-2">
      <span className="text-sm">{node.title}</span>
      {nodeStatusBadge(node.status)}
    </span>
  );

  return (
    <li data-testid="roadmap-blocking-chain-node">
      {hasChildren ? (
        <details open>
          <summary className="cursor-pointer marker:text-muted-foreground">{label}</summary>
          <ul className="mt-1 space-y-1 pl-4">
            {node.blockedBy.map((child) => (
              <BlockingChainNodeItem key={`${child.itemType}-${child.itemId}`} node={child} />
            ))}
          </ul>
        </details>
      ) : (
        label
      )}
    </li>
  );
}

/**
 * Read-only, collapsible section showing the FULL upstream blocking chain
 * (every hop, every branch) for a BLOCKED Story/Task, beneath the existing
 * "Blocked by" (direct blockers) section in RoadmapGraphDetailPanel. Purely
 * presentational — the parent (RoadmapGraphDetailPanel) owns fetching via
 * `useBlockingChain` and passes the result down, so this stays easy to unit
 * test in isolation.
 */
export default function BlockingChainSection({ chain, isLoading }: Props) {
  if (isLoading) {
    return (
      <div data-testid="roadmap-blocking-chain-loading" className="pt-3 border-t space-y-2">
        <h3 className="text-sm font-medium text-muted-foreground">Blocking chain</h3>
        <Skeleton className="h-8 w-full" />
      </div>
    );
  }

  // Mirrors ExternalBlockersSection's empty-state convention: nothing worth
  // showing renders nothing, rather than an empty section shell.
  if (!chain || chain.blockedBy.length === 0) return null;

  return (
    <div data-testid="roadmap-blocking-chain" className="pt-3 border-t space-y-2">
      <h3 className="text-sm font-medium text-muted-foreground">Blocking chain</h3>
      <ul className="space-y-1">
        {chain.blockedBy.map((node) => (
          <BlockingChainNodeItem key={`${node.itemType}-${node.itemId}`} node={node} />
        ))}
      </ul>
      {chain.truncated && (
        <p data-testid="roadmap-blocking-chain-truncated" className="text-xs text-muted-foreground">
          Not all blockers shown — the chain is very large.
        </p>
      )}
    </div>
  );
}
