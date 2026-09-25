/**
 * Icon shape, label weight and row tint must stay distinct per level: they, not
 * color, keep severities apart where a `status-*` hue is weak on the log surface
 * (docs/decisions/2026-09-25---01-log-severity-redundant-encoding.md). Keep every
 * class a complete literal — Tailwind silently drops an interpolated class name.
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

/**
 * Returns the treatment for a log level, case-insensitively, or the neutral
 * fallback for any level outside the server's info/warn/error enum.
 */
export function logLevelStyle(level: string): LogLevelStyle {
  switch (level?.toLowerCase()) {
    case "info":
      return {
        Icon: Info,
        text: "text-status-info",
        weight: "",
        border: "border-status-info",
        row: "",
      };
    case "warn":
      return {
        Icon: TriangleAlert,
        text: "text-status-warning",
        weight: "font-medium",
        border: "border-status-warning",
        row: "bg-status-warning/10",
      };
    case "error":
      return {
        Icon: OctagonAlert,
        text: "text-status-error",
        weight: "font-semibold",
        border: "border-status-error",
        row: "bg-status-error/10",
      };
    default:
      return {
        Icon: Minus,
        text: "text-status-neutral",
        weight: "",
        border: "border-status-neutral",
        row: "",
      };
  }
}
