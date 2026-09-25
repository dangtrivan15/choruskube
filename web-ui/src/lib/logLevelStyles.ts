/**
 * Per-log-level display metadata for the execution log viewer — icon, text
 * color, label weight, row accent border and row tint. Mirrors the shape of
 * `statusColors.ts` / `priorityMeta.ts` (a level string maps to structured,
 * token-based classes) but is its own module because log level (info/warn/
 * error) is a different domain than run/node status, with a different
 * return shape (icon + color + weight + border + row tint, not badge
 * classes).
 *
 * Severity is carried redundantly — icon shape, label weight and row tint
 * are non-color channels that stay distinguishable even where a `status-*`
 * hue is weak against the log surface in one theme (docs/decisions).
 * `text` and `weight` are separate fields so the icon can reuse the color
 * class without picking up the label's font-weight class.
 *
 * All classes use semantic `status-*` tokens (CSS custom properties
 * registered via Tailwind `@theme` in index.css) — never a raw hex or
 * Tailwind palette literal — so the same classes recolor automatically
 * between light and dark.
 */
import type { LucideIcon } from "lucide-react";
import { Info, TriangleAlert, OctagonAlert, Minus } from "lucide-react";

export interface LogLevelStyle {
  /** Leading severity icon. */
  Icon: LucideIcon;
  /** Text color class, shared by the icon and the label. */
  text: string;
  /** Font-weight class for the label only. */
  weight: string;
  /** Left accent border class for the row. */
  border: string;
  /** Row background tint class — empty for levels that shouldn't tint. */
  row: string;
}

const LOG_LEVEL_STYLES: Record<string, LogLevelStyle> = {
  info: {
    Icon: Info,
    text: "text-status-info",
    weight: "",
    border: "border-status-info",
    row: "",
  },
  warn: {
    Icon: TriangleAlert,
    text: "text-status-warning",
    weight: "font-medium",
    border: "border-status-warning",
    row: "bg-status-warning/10",
  },
  error: {
    Icon: OctagonAlert,
    text: "text-status-error",
    weight: "font-semibold",
    border: "border-status-error",
    row: "bg-status-error/10",
  },
};

const NEUTRAL_LOG_LEVEL_STYLE: LogLevelStyle = {
  Icon: Minus,
  text: "text-status-neutral",
  weight: "",
  border: "border-status-neutral",
  row: "",
};

/**
 * Returns the icon/color/weight/border/row treatment for a log level,
 * case-insensitively, or the neutral fallback for any level outside the
 * server's info/warn/error enum.
 */
export function logLevelStyle(level: string): LogLevelStyle {
  return LOG_LEVEL_STYLES[level?.toLowerCase()] ?? NEUTRAL_LOG_LEVEL_STYLE;
}
