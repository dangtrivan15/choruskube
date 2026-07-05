import { useEffect, useRef } from "react";
import { useNavigate } from "react-router";
import { X, Trash2, CheckCheck, Info, CheckCircle, AlertTriangle, XCircle } from "lucide-react";
import { useActivityFeed } from "@/hooks/useActivityFeed";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import type { ActivityFeedEntry } from "@/lib/types";
import { formatDistanceToNow } from "date-fns";

interface ActivityFeedPanelProps {
  open: boolean;
  onClose: () => void;
}

const variantIcon: Record<ActivityFeedEntry["variant"], typeof Info> = {
  info: Info,
  success: CheckCircle,
  warning: AlertTriangle,
  error: XCircle,
};

const variantColor: Record<ActivityFeedEntry["variant"], string> = {
  info: "text-status-info",
  success: "text-status-success",
  warning: "text-status-warning",
  error: "text-status-error",
};

export default function ActivityFeedPanel({ open, onClose }: ActivityFeedPanelProps) {
  const { entries, markAllRead, clearAll } = useActivityFeed();
  const navigate = useNavigate();
  const panelRef = useRef<HTMLDivElement>(null);
  const isMobile = useMobileBreakpoint();

  // Mark all as read when panel opens
  useEffect(() => {
    if (open) {
      markAllRead();
    }
  }, [open, markAllRead]);

  // Close on Escape key
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [open, onClose]);

  // Close on click outside
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) {
        onClose();
      }
    };
    // Delay to avoid closing immediately on the same click that opened it
    const timeout = setTimeout(() => {
      document.addEventListener("mousedown", handler);
    }, 0);
    return () => {
      clearTimeout(timeout);
      document.removeEventListener("mousedown", handler);
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div
      ref={panelRef}
      role="dialog"
      aria-label="Activity feed"
      className={
        isMobile
          ? "fixed inset-0 z-50 flex flex-col bg-popover"
          : "absolute right-0 top-12 z-50 flex h-[min(480px,calc(100vh-4rem))] w-80 flex-col rounded-lg border bg-popover shadow-lg"
      }
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b px-4 py-3">
        <h2 className="text-sm font-semibold">Activity</h2>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={() => markAllRead()}
            className="rounded p-1 text-muted-foreground hover:text-foreground"
            aria-label="Mark all as read"
            title="Mark all as read"
          >
            <CheckCheck className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={() => clearAll()}
            className="rounded p-1 text-muted-foreground hover:text-foreground"
            aria-label="Clear all"
            title="Clear all"
          >
            <Trash2 className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={onClose}
            className="rounded p-1 text-muted-foreground hover:text-foreground"
            aria-label="Close activity feed"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Entries */}
      <div className="flex-1 overflow-y-auto">
        {entries.length === 0 ? (
          <div className="flex h-full items-center justify-center p-6 text-sm text-muted-foreground">
            No activity yet
          </div>
        ) : (
          <ul className="divide-y" role="list">
            {entries.map((entry) => {
              const Icon = variantIcon[entry.variant];
              return (
                <li key={entry.id} className="flex gap-3 px-4 py-3">
                  <Icon className={`mt-0.5 h-4 w-4 shrink-0 ${variantColor[entry.variant]}`} />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm leading-snug">
                      {entry.actionUrl ? (
                        <button
                          type="button"
                          className="text-left hover:underline"
                          onClick={() => {
                            navigate(entry.actionUrl!);
                            onClose();
                          }}
                        >
                          {entry.message}
                        </button>
                      ) : (
                        entry.message
                      )}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {formatDistanceToNow(entry.timestamp, { addSuffix: true })}
                    </p>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </div>
  );
}
