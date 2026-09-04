/**
 * Shared status-to-CSS-class mappings for status badges and DAG nodes.
 *
 * All classes use semantic status tokens (`bg-status-*`, `text-status-*`,
 * `border-status-*`) defined as CSS custom properties in `index.css` and
 * registered with Tailwind via `@theme`.
 *
 * Opacity values are a presentational concern: badges use `/15` and `/20`,
 * while DAG nodes use `/10` and `/60`.
 */

/** Flat class string for `<Badge className={…}>` usage. */
export function statusBadgeClass(status: string): string {
  switch (status) {
    case "completed":
      return "bg-status-success/15 text-status-success border-status-success/20";
    case "failed":
      return "bg-status-error/15 text-status-error border-status-error/20";
    case "running":
      return "bg-status-info/15 text-status-info border-status-info/20";
    case "awaiting_human":
      return "bg-status-warning/15 text-status-warning border-status-warning/20";
    case "awaiting_retry":
      return "bg-status-warning/15 text-status-warning border-status-warning/20";
    case "paused":
      return "bg-status-accent/15 text-status-accent border-status-accent/20";
    case "cancelled":
    case "pending":
    default:
      return "bg-status-neutral/15 text-status-neutral border-status-neutral/20";
  }
}

/** Structured tokens for components that need separate bg / border / text classes (e.g. DagNode). */
export interface StatusColorTokens {
  bg: string;
  border: string;
  text: string;
}

export function statusColorTokens(status: string): StatusColorTokens {
  switch (status) {
    case "completed":
      return { bg: "bg-status-success", border: "border-status-success", text: "text-status-success" };
    case "failed":
      return { bg: "bg-status-error", border: "border-status-error", text: "text-status-error" };
    case "running":
      return { bg: "bg-status-info", border: "border-status-info", text: "text-status-info" };
    case "awaiting_human":
      return { bg: "bg-status-warning", border: "border-status-warning", text: "text-status-warning" };
    case "awaiting_retry":
      return { bg: "bg-status-warning", border: "border-status-warning", text: "text-status-warning" };
    case "paused":
      return { bg: "bg-status-accent", border: "border-status-accent", text: "text-status-accent" };
    case "cancelled":
    case "pending":
    default:
      return { bg: "bg-status-neutral", border: "border-status-neutral", text: "text-status-neutral" };
  }
}
