import { priorityMeta } from "@/lib/priorityMeta";
import { cn } from "@/lib/utils";

interface PriorityBadgeProps {
  /**
   * The priority to render. Typed loosely (`string | null | undefined`) rather
   * than `Priority` so display-only call sites (board cards, graph/timeline
   * nodes) can pass a possibly-stale/absent value straight from cached data:
   * an unknown or missing value renders nothing rather than crashing.
   */
  priority: string | null | undefined;
  /**
   * `"default"` (detail pages, list rows) vs `"compact"` (cramped board card
   * rows, graph/timeline markers) — same icon + label either way; only the
   * padding/text/icon size differ, mirroring `ReadinessBadge`/`StalledBadge`.
   */
  size?: "default" | "compact";
  className?: string;
  "data-testid"?: string;
}

/**
 * Shared priority indicator (High/Medium/Low) — the single rendering of
 * `priorityMeta.ts`'s per-priority identity, reused everywhere an Epic/Story
 * priority appears (detail pages, list rows, board cards, graph/timeline
 * nodes). Unlike `ReadinessBadge`/`StalledBadge`, every valid priority renders
 * a visible chip (there is no "silence means fine" level — an item always has a
 * priority); but an *unknown* value (absent from stale cached data, or an older
 * API pod's payload) renders nothing, matching the null-tolerant `priorityMeta`
 * accessor.
 */
export default function PriorityBadge({
  priority,
  size = "default",
  className,
  "data-testid": dataTestId,
}: PriorityBadgeProps) {
  const meta = priorityMeta(priority);
  if (!meta) return null;

  const Icon = meta.Icon;
  const compact = size === "compact";

  return (
    <span
      data-testid={dataTestId ?? `priority-badge-${priority}`}
      title={`${meta.label} priority`}
      className={cn(
        "inline-flex w-fit items-center font-medium rounded-full",
        meta.textClass,
        meta.bgClass,
        compact
          ? "gap-0.5 px-1.5 py-0.5 text-[10px]"
          : cn("gap-1 border px-2 py-0.5 text-xs", meta.borderClass),
        className,
      )}
    >
      <Icon className={compact ? "size-2.5" : "size-3"} />
      {meta.label}
    </span>
  );
}
