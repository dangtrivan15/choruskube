import { Clock } from "lucide-react";
import { cn } from "@/lib/utils";

interface StalledBadgeProps {
  stalled: boolean;
  /**
   * `"default"` vs `"compact"` — same icon, text, and render-nothing-unless-`stalled` contract
   * either way; only the padding/text/icon size differ, mirroring `ReadinessBadge`'s own variant.
   */
  size?: "default" | "compact";
  "data-testid"?: string;
}

/**
 * Shared "Stalled" activity indicator, mirroring `ReadinessBadge`'s structure exactly: a plain
 * `boolean` prop instead of a tri-state readiness (there is no third "unknown" state for
 * staleness), rendering nothing at all unless `stalled` is `true`.
 */
export default function StalledBadge({ stalled, size = "default", "data-testid": dataTestId }: StalledBadgeProps) {
  if (!stalled) return null;

  const compact = size === "compact";

  return (
    <span
      data-testid={dataTestId}
      title="Stalled — no recent activity"
      className={cn(
        "inline-flex items-center font-medium text-status-neutral bg-status-neutral/15 rounded-full",
        compact ? "gap-0.5 px-1.5 py-0.5 text-[10px]" : "gap-1 border border-status-neutral/20 px-2 py-0.5 text-xs",
      )}
    >
      <Clock className={compact ? "size-2.5" : "size-3"} />
      Stalled
    </span>
  );
}
