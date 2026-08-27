import { Lock } from "lucide-react";
import { cn } from "@/lib/utils";
import type { Readiness } from "@/lib/types";

interface ReadinessBadgeProps {
  readiness: Readiness | null;
  /**
   * `"default"` (the Roadmap Graph detail panel and the Story/Task list rows)
   * vs `"compact"` (the Epic board card's cramped Story row) — same icon,
   * text, and render-nothing-unless-BLOCKED contract either way; only the
   * padding/text/icon size differ so the badge doesn't overwhelm a tight
   * card row.
   */
  size?: "default" | "compact";
  "data-testid"?: string;
}

/**
 * Shared "Blocked" dependency-readiness indicator — the single
 * implementation behind all four call sites that render it (the Roadmap
 * Graph detail panel, the Epic board card's Story rows, and the Epic/Story
 * detail pages' Story/Task rows), so a future visual or copy change happens
 * in one place instead of drifting across four near-identical inline copies.
 *
 * Renders nothing at all for `null` or `"READY"` — matching the existing
 * "silence means fine" convention: a blocked item gets a visible callout, a
 * ready (or not-yet-computed) one gets no badge rather than a redundant
 * "Ready" pill.
 */
export default function ReadinessBadge({
  readiness,
  size = "default",
  "data-testid": dataTestId,
}: ReadinessBadgeProps) {
  if (readiness !== "BLOCKED") return null;

  const compact = size === "compact";

  return (
    <span
      data-testid={dataTestId}
      title="Blocked by an unfinished dependency"
      className={cn(
        "inline-flex items-center font-medium text-status-warning bg-status-warning/15 rounded-full",
        compact ? "gap-0.5 px-1.5 py-0.5 text-[10px]" : "gap-1 border border-status-warning/20 px-2 py-0.5 text-xs",
      )}
    >
      <Lock className={compact ? "size-2.5" : "size-3"} />
      Blocked
    </span>
  );
}
