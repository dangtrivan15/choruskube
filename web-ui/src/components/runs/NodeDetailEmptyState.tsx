import { classifyActiveNodes } from "@/lib/runFocus";
import { formatNodeLabel } from "./DagNode";
import type { RunResponse } from "@/lib/types";

interface NodeDetailEmptyStateProps {
  run: RunResponse;
  onSelectNode: (nodeId: string) => void;
}

/**
 * What the docked panel/sheet shows when no node is selected — a hint, plus
 * shortcuts to whatever needs attention or is currently running, so the panel
 * is never simply blank while something is actually happening in the run.
 */
export default function NodeDetailEmptyState({ run, onSelectNode }: NodeDetailEmptyStateProps) {
  if (!run.graphSnapshot) {
    return (
      <div data-testid="node-detail-empty" className="p-4 text-sm text-muted-foreground">
        No graph snapshot.
      </div>
    );
  }

  const { attention, running } = classifyActiveNodes(run);

  return (
    <div data-testid="node-detail-empty" className="space-y-4 p-4 text-sm">
      <p className="text-muted-foreground">Select a node in the graph to see its details.</p>
      {attention.length > 0 && (
        <div className="space-y-1.5">
          <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Needs attention
          </h4>
          <ul className="space-y-1">
            {attention.map((node) => (
              <li key={node.template_node_id}>
                <button
                  type="button"
                  data-testid="node-detail-empty-item"
                  data-node-id={node.template_node_id}
                  className="w-full rounded-md border px-2.5 py-1.5 text-left hover:bg-muted"
                  onClick={() => onSelectNode(node.template_node_id)}
                >
                  {formatNodeLabel(node.label)}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      {running.length > 0 && (
        <div className="space-y-1.5">
          <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Running
          </h4>
          <ul className="space-y-1">
            {running.map((node) => (
              <li key={node.template_node_id}>
                <button
                  type="button"
                  data-testid="node-detail-empty-item"
                  data-node-id={node.template_node_id}
                  className="w-full rounded-md border px-2.5 py-1.5 text-left hover:bg-muted"
                  onClick={() => onSelectNode(node.template_node_id)}
                >
                  {formatNodeLabel(node.label)}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
