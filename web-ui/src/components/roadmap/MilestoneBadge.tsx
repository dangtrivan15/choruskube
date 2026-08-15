import { milestoneMeta } from "@/lib/milestoneMeta";
import { cn } from "@/lib/utils";
import type { MilestoneRef } from "@/lib/types";

interface MilestoneBadgeProps {
  /**
   * The Epic's assigned Milestone, or `null`/`undefined` when unassigned — renders nothing in
   * that case (an Epic with no Milestone shows no chip at all, unlike `PriorityBadge` which
   * always has a value to show).
   */
  milestone: MilestoneRef | null | undefined;
  /**
   * `"default"` (detail pages, list rows) vs `"compact"` (cramped board card rows, timeline
   * markers) — mirrors `PriorityBadge`'s two sizes.
   */
  size?: "default" | "compact";
  className?: string;
  "data-testid"?: string;
}

/**
 * Release-grouping chip for an Epic's assigned Milestone (Decision 1/2 of the "Group Epics under
 * a named Milestone / Release" feature) — reused everywhere a Milestone reference appears
 * (Roadmap list rows, Epic detail page). See `milestoneMeta.ts` for the per-Milestone accent.
 */
export default function MilestoneBadge({
  milestone,
  size = "default",
  className,
  "data-testid": dataTestId,
}: MilestoneBadgeProps) {
  if (!milestone) return null;

  const meta = milestoneMeta(milestone.id);
  const Icon = meta.Icon;
  const compact = size === "compact";

  return (
    <span
      data-testid={dataTestId ?? "milestone-badge"}
      title={milestone.name}
      className={cn(
        "inline-flex w-fit min-w-0 items-center font-medium rounded-full",
        meta.textClass,
        meta.bgClass,
        compact
          ? "gap-0.5 px-1.5 py-0.5 text-[10px]"
          : cn("gap-1 border px-2 py-0.5 text-xs", meta.borderClass),
        className,
      )}
    >
      <Icon className={compact ? "size-2.5 shrink-0" : "size-3 shrink-0"} />
      <span className="min-w-0 truncate">{milestone.name}</span>
    </span>
  );
}
