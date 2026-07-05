import type { NodeAnalytics } from "@/lib/types";
import TruncatedText from "@/components/ui/TruncatedText";

interface NodeTableProps {
  nodes: NodeAnalytics[];
}

export default function NodeTable({ nodes }: NodeTableProps) {
  if (nodes.length === 0) {
    return (
      <div className="flex h-32 items-center justify-center text-sm text-muted-foreground">
        No node execution data for this period
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="pb-2 pr-4 font-medium">Node</th>
            <th className="pb-2 pr-4 text-right font-medium">Executions</th>
            <th className="pb-2 pr-4 text-right font-medium">Completed</th>
            <th className="pb-2 pr-4 text-right font-medium">Failed</th>
            <th className="pb-2 text-right font-medium">Success Rate</th>
          </tr>
        </thead>
        <tbody>
          {nodes.map((n) => (
            <tr key={n.label} className="border-b last:border-0">
              <td className="py-2 pr-4 font-medium max-w-xs">
                <TruncatedText>{n.label}</TruncatedText>
              </td>
              <td className="py-2 pr-4 text-right tabular-nums">{n.executionCount}</td>
              <td className="py-2 pr-4 text-right tabular-nums text-status-success">{n.completedCount}</td>
              <td className="py-2 pr-4 text-right tabular-nums text-status-error">{n.failedCount}</td>
              <td className="py-2 text-right tabular-nums">{n.successRate.toFixed(1)}%</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
