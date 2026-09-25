import { useEffect, useRef } from "react";
import { format } from "date-fns";
import { Loader2 } from "lucide-react";
import { useNodeLogs } from "@/hooks/useRuns";
import { logLevelStyle } from "@/lib/logLevelStyles";
import { cn } from "@/lib/utils";

interface ExecutionLogsProps {
  runId: string;
  nodeExecId: string;
  isActive: boolean;
}

export default function ExecutionLogs({
  runId,
  nodeExecId,
  isActive,
}: ExecutionLogsProps) {
  const { data: logs, isLoading } = useNodeLogs(runId, nodeExecId, isActive);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [logs]);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8 text-muted-foreground">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
        Loading logs...
      </div>
    );
  }

  if (!logs || logs.length === 0) {
    return (
      <p className="py-4 text-center text-sm text-muted-foreground">
        No logs available.
      </p>
    );
  }

  return (
    <div
      ref={scrollRef}
      data-testid="execution-logs"
      className="max-h-80 overflow-y-auto rounded-md border bg-muted/30 p-3 font-mono text-xs"
    >
      {logs.map((log) => {
        const { Icon, text, weight, border, row } = logLevelStyle(log.level);
        return (
          <div
            key={log.id}
            data-testid="log-row"
            data-level={log.level}
            className={cn("flex items-start gap-2 border-l-2 py-0.5 pl-2", border, row)}
          >
            <span className="shrink-0 text-muted-foreground">
              {format(new Date(log.timestamp), "HH:mm:ss.SSS")}
            </span>
            <Icon aria-hidden className={cn("h-3.5 w-3.5 shrink-0 self-center", text)} />
            <span
              data-testid="log-level"
              className={cn("shrink-0 w-12 text-right uppercase", text, weight)}
            >
              {log.level}
            </span>
            <span className="break-all">{log.message}</span>
          </div>
        );
      })}
    </div>
  );
}
