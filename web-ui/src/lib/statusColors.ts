/**
 * Shared status-to-CSS-class mappings for status badges, DAG nodes, and the
 * `StatusDot` / `StatusCallout` primitives. What each tone means is recorded
 * in `docs/decisions/2026-09-24---01-status-tone-vocabulary.md`; how a label
 * stays ink while the tone lives on marks only is recorded in
 * `docs/decisions/2026-09-27---01-status-ink-labels-and-contrast-gate.md`.
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
  /** Flat class string for `<Badge className={…}>` usage — a leading `::before` hue dot, word in `text-foreground`. */
  badge: string;
  bg: string;
  border: string;
  /** Mark color only (icon/glyph) — never apply to a text word; see `docs/decisions/2026-09-27---01-status-ink-labels-and-contrast-gate.md`. */
  text: string;
  /** Class for a small solid-color status dot. */
  dot: string;
  /** Class for a tinted, bordered callout block (see `StatusCallout`) — body text is `text-foreground`. */
  callout: string;
  /** Opaque 15% tint over `--popover` — replaces a translucent tint modifier so it reads the same over any backdrop. */
  tint: string;
  /** Opaque 25% tint over `--popover`, for hover/emphasis states. */
  tintStrong: string;
  /** Softer border alpha for a tint background pairing (`/60` vs. the default `/40`). */
  borderSoft: string;
}

export const STATUS_TONE_CLASSES: Record<StatusTone, StatusToneClassSet> = {
  success: {
    badge:
      "bg-tint-status-success border-status-success/40 text-foreground before:inline-block before:size-1.5 before:shrink-0 before:rounded-full before:bg-status-success before:content-['']",
    bg: "bg-status-success",
    border: "border-status-success",
    text: "text-status-success",
    dot: "size-2 shrink-0 rounded-full bg-status-success",
    callout: "border-status-success/40 bg-tint-status-success text-foreground",
    tint: "bg-tint-status-success",
    tintStrong: "bg-tint-strong-status-success",
    borderSoft: "border-status-success/60",
  },
  error: {
    badge:
      "bg-tint-status-error border-status-error/40 text-foreground before:inline-block before:size-1.5 before:shrink-0 before:rounded-full before:bg-status-error before:content-['']",
    bg: "bg-status-error",
    border: "border-status-error",
    text: "text-status-error",
    dot: "size-2 shrink-0 rounded-full bg-status-error",
    callout: "border-status-error/40 bg-tint-status-error text-foreground",
    tint: "bg-tint-status-error",
    tintStrong: "bg-tint-strong-status-error",
    borderSoft: "border-status-error/60",
  },
  info: {
    badge:
      "bg-tint-status-info border-status-info/40 text-foreground before:inline-block before:size-1.5 before:shrink-0 before:rounded-full before:bg-status-info before:content-['']",
    bg: "bg-status-info",
    border: "border-status-info",
    text: "text-status-info",
    dot: "size-2 shrink-0 rounded-full bg-status-info",
    callout: "border-status-info/40 bg-tint-status-info text-foreground",
    tint: "bg-tint-status-info",
    tintStrong: "bg-tint-strong-status-info",
    borderSoft: "border-status-info/60",
  },
  warning: {
    badge:
      "bg-tint-status-warning border-status-warning/40 text-foreground before:inline-block before:size-1.5 before:shrink-0 before:rounded-full before:bg-status-warning before:content-['']",
    bg: "bg-status-warning",
    border: "border-status-warning",
    text: "text-status-warning",
    dot: "size-2 shrink-0 rounded-full bg-status-warning",
    callout: "border-status-warning/40 bg-tint-status-warning text-foreground",
    tint: "bg-tint-status-warning",
    tintStrong: "bg-tint-strong-status-warning",
    borderSoft: "border-status-warning/60",
  },
  accent: {
    badge:
      "bg-tint-status-accent border-status-accent/40 text-foreground before:inline-block before:size-1.5 before:shrink-0 before:rounded-full before:bg-status-accent before:content-['']",
    bg: "bg-status-accent",
    border: "border-status-accent",
    text: "text-status-accent",
    dot: "size-2 shrink-0 rounded-full bg-status-accent",
    callout: "border-status-accent/40 bg-tint-status-accent text-foreground",
    tint: "bg-tint-status-accent",
    tintStrong: "bg-tint-strong-status-accent",
    borderSoft: "border-status-accent/60",
  },
  neutral: {
    badge:
      "bg-tint-status-neutral border-status-neutral/40 text-foreground before:inline-block before:size-1.5 before:shrink-0 before:rounded-full before:bg-status-neutral before:content-['']",
    bg: "bg-status-neutral",
    border: "border-status-neutral",
    text: "text-status-neutral",
    dot: "size-2 shrink-0 rounded-full bg-status-neutral",
    callout: "border-status-neutral/40 bg-tint-status-neutral text-foreground",
    tint: "bg-tint-status-neutral",
    tintStrong: "bg-tint-strong-status-neutral",
    borderSoft: "border-status-neutral/60",
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
  tint: string;
  tintStrong: string;
  borderSoft: string;
}

export function statusColorTokens(status: string): StatusColorTokens {
  const { bg, border, text, tint, tintStrong, borderSoft } = STATUS_TONE_CLASSES[statusTone(status)];
  return { bg, border, text, tint, tintStrong, borderSoft };
}
