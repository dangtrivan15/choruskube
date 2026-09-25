/**
 * Shared status-to-CSS-class mappings for status badges, DAG nodes, and the
 * `StatusDot` / `StatusCallout` primitives. What each tone means is recorded
 * in `docs/decisions/2026-09-24---01-status-tone-vocabulary.md`.
 *
 * All classes use semantic status tokens (`bg-status-*`, `text-status-*`,
 * `border-status-*`) defined as CSS custom properties in `index.css` and
 * registered with Tailwind via `@theme`.
 *
 * Every recipe below is a complete string literal, never assembled with a
 * template (`` `bg-status-${tone}` ``): Tailwind only generates the classes
 * it finds written out in full in source, so an interpolated class name
 * compiles fine but renders with no color and no error.
 */
import type { CredentialHealthStatus, ProvisioningStatus } from "@/lib/types";

export type StatusTone = "success" | "error" | "info" | "warning" | "accent" | "neutral";

interface StatusToneClassSet {
  /** Flat class string for `<Badge className={…}>` usage. */
  badge: string;
  bg: string;
  border: string;
  text: string;
  /** Class for a small solid-color status dot. */
  dot: string;
  /** Class for a tinted, bordered callout block (see `StatusCallout`). */
  callout: string;
}

export const STATUS_TONE_CLASSES: Record<StatusTone, StatusToneClassSet> = {
  success: {
    badge: "bg-status-success/15 text-status-success border-status-success/20",
    bg: "bg-status-success",
    border: "border-status-success",
    text: "text-status-success",
    dot: "size-2 shrink-0 rounded-full bg-status-success",
    callout: "border-status-success/40 bg-status-success/10 text-foreground",
  },
  error: {
    badge: "bg-status-error/15 text-status-error border-status-error/20",
    bg: "bg-status-error",
    border: "border-status-error",
    text: "text-status-error",
    dot: "size-2 shrink-0 rounded-full bg-status-error",
    callout: "border-status-error/40 bg-status-error/10 text-foreground",
  },
  info: {
    badge: "bg-status-info/15 text-status-info border-status-info/20",
    bg: "bg-status-info",
    border: "border-status-info",
    text: "text-status-info",
    dot: "size-2 shrink-0 rounded-full bg-status-info",
    callout: "border-status-info/40 bg-status-info/10 text-foreground",
  },
  warning: {
    badge: "bg-status-warning/15 text-status-warning border-status-warning/20",
    bg: "bg-status-warning",
    border: "border-status-warning",
    text: "text-status-warning",
    dot: "size-2 shrink-0 rounded-full bg-status-warning",
    callout: "border-status-warning/40 bg-status-warning/10 text-foreground",
  },
  accent: {
    badge: "bg-status-accent/15 text-status-accent border-status-accent/20",
    bg: "bg-status-accent",
    border: "border-status-accent",
    text: "text-status-accent",
    dot: "size-2 shrink-0 rounded-full bg-status-accent",
    callout: "border-status-accent/40 bg-status-accent/10 text-foreground",
  },
  neutral: {
    badge: "bg-status-neutral/15 text-status-neutral border-status-neutral/20",
    bg: "bg-status-neutral",
    border: "border-status-neutral",
    text: "text-status-neutral",
    dot: "size-2 shrink-0 rounded-full bg-status-neutral",
    callout: "border-status-neutral/40 bg-status-neutral/10 text-foreground",
  },
};

/** Maps a run/node status string to its tone. Unknown statuses fall back to neutral. */
export function statusTone(status: string): StatusTone {
  switch (status) {
    case "completed":
      return "success";
    case "failed":
      return "error";
    case "running":
      return "info";
    case "awaiting_human":
    case "awaiting_retry":
      return "warning";
    case "paused":
      return "accent";
    case "cancelled":
    case "pending":
    default:
      return "neutral";
  }
}

export function provisioningTone(status: ProvisioningStatus): StatusTone {
  switch (status) {
    case "ready":
      return "success";
    case "failed":
      return "error";
    case "provisioning":
      return "info";
    case "pending":
    default:
      return "neutral";
  }
}

export function credentialHealthTone(status: CredentialHealthStatus): StatusTone {
  switch (status) {
    case "VALID":
      return "success";
    case "EXPIRED":
    case "INSUFFICIENT_PERMISSIONS":
      return "error";
    case "UNREACHABLE":
      return "warning";
    default:
      return "neutral";
  }
}

/** Flat class string for `<Badge className={…}>` usage. */
export function statusBadgeClass(status: string): string {
  return STATUS_TONE_CLASSES[statusTone(status)].badge;
}

/** Structured tokens for components that need separate bg / border / text classes (e.g. DagNode). */
export interface StatusColorTokens {
  bg: string;
  border: string;
  text: string;
}

export function statusColorTokens(status: string): StatusColorTokens {
  const { bg, border, text } = STATUS_TONE_CLASSES[statusTone(status)];
  return { bg, border, text };
}
