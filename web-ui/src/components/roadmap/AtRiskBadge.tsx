import { AlertTriangle } from "lucide-react";
import { cn } from "@/lib/utils";
import { STATUS_TONE_CLASSES } from "@/lib/statusColors";

interface AtRiskBadgeProps {
  atRisk: boolean;
  /** `MilestoneResponse.atRiskItemCount` — how many Epics/Stories under it are individually at risk. */
  count: number;
  /**
   * `"default"` vs `"compact"` — same icon, text, and render-nothing-unless-`atRisk` contract
   * either way; only the padding/text/icon size differ, mirroring `StalledBadge`'s own variant.
   */
  size?: "default" | "compact";
  "data-testid"?: string;
}

/**
 * Shared "At Risk" indicator for a Milestone whose target date has passed while it still has
 * incomplete Epics — mirrors `StalledBadge`'s structure exactly: a plain `boolean` prop, warning
 * styling, rendering nothing at all unless `atRisk` is `true`.
 */
export default function AtRiskBadge({
  atRisk,
  count,
  size = "default",
  "data-testid": dataTestId,
}: AtRiskBadgeProps) {
  if (!atRisk) return null;

  const compact = size === "compact";

  return (
    <span
      data-testid={dataTestId}
      title={`At risk — ${count} item${count === 1 ? "" : "s"} past target date`}
      className={cn(
        "inline-flex items-center rounded-full font-medium",
        STATUS_TONE_CLASSES.warning.badge,
        compact ? "gap-0.5 px-1.5 py-0.5 text-[10px]" : "gap-1 border px-2 py-0.5 text-xs",
      )}
    >
      <AlertTriangle className={compact ? "size-2.5" : "size-3"} />
      At Risk{count > 0 ? ` (${count})` : ""}
    </span>
  );
}
