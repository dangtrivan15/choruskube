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
  /** Icon-only tone color — apply to the glyph, never to the level word. */
  icon: string;
  /** Label class — ink (`text-foreground`) plus the level's font-weight. */
  label: string;
  /** Font-weight class for the label only. */
  weight: string;
  /** Left accent border class for the row. */
  border: string;
  /** Opaque row tint class (`bg-tint-status-*`) — empty for levels that shouldn't tint. */
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
        icon: "text-status-info",
        label: "text-foreground",
        weight: "",
        border: "border-status-info",
        row: "",
      };
    case "warn":
      return {
        Icon: TriangleAlert,
        icon: "text-status-warning",
        label: "text-foreground font-medium",
        weight: "font-medium",
        border: "border-status-warning",
        row: "bg-tint-status-warning",
      };
    case "error":
      return {
        Icon: OctagonAlert,
        icon: "text-status-error",
        label: "text-foreground font-semibold",
        weight: "font-semibold",
        border: "border-status-error",
        row: "bg-tint-status-error",
      };
    default:
      return {
        Icon: Minus,
        icon: "text-status-neutral",
        label: "text-foreground",
        weight: "",
        border: "border-status-neutral",
        row: "",
      };
  }
}
