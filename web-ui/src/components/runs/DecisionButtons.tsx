import { CheckCircle, XCircle, Loader2, RotateCcw, FileEdit } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import type { GateTrigger } from "@/lib/decisions";

/**
 * Default options shown when the server didn't supply `decisionOptions` (older
 * API pod during a rolling deploy). Mirrors the pre-v23 contract.
 */
export const LEGACY_DECISION_OPTIONS = ["approved", "rejected"] as const;

interface DecisionMeta {
  label: string;
  icon: LucideIcon;
  /** maps to Button's `variant` prop */
  variant: "default" | "destructive" | "secondary";
  /** when true, disable the button until the operator has typed feedback */
  requiresFeedback: boolean;
  /** extra className overrides — used for the green approve button */
  className?: string;
  /** tooltip text */
  title?: string;
}

const DECISION_META: Record<string, DecisionMeta> = {
  approved: {
    label: "Approve",
    icon: CheckCircle,
    variant: "default",
    requiresFeedback: false,
    className: "bg-status-success text-background hover:bg-status-success/90",
  },
  rejected: {
    label: "Reject",
    icon: XCircle,
    variant: "destructive",
    requiresFeedback: true,
  },
  rereview: {
    label: "Re-review",
    icon: RotateCcw,
    variant: "secondary",
    requiresFeedback: true,
    title: "Re-run the upstream reviewer with the guidance typed above",
  },
  redraft: {
    label: "Redraft",
    icon: FileEdit,
    variant: "secondary",
    requiresFeedback: true,
    title: "Send back for a full re-author using the guidance typed above",
  },
};

function pascalCase(value: string): string {
  return value
    .split(/[_\s-]/)
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1).toLowerCase())
    .join(" ");
}

function metaFor(option: string): DecisionMeta {
  const meta = DECISION_META[option];
  if (meta) return meta;
  // Unknown server-side condition (e.g., a future gate type the UI hasn't been
  // taught yet). Render a generic secondary button so the workflow can still
  // progress, but flag for developer attention.
  if (typeof console !== "undefined") {
    console.warn(`[DecisionButtons] Unknown decision option "${option}" — falling back to generic button.`);
  }
  return {
    label: pascalCase(option),
    icon: CheckCircle, // unused; we render iconless in the unknown case
    variant: "secondary",
    requiresFeedback: false,
  };
}

/**
 * Special-case label overrides driven by the *trigger* that brought us to the
 * gate. The classic case is `alternative_proposal`: the reviewer proposed a
 * different design, so "Approve" reads as "Stay with current spec" and
 * "Redraft" reads as "Accept alternative".
 */
function labelFor(option: string, trigger: GateTrigger | null): string {
  const meta = metaFor(option);
  if (trigger?.kind === "alternative_proposal") {
    if (option === "approved") return "Stay with current spec";
    if (option === "redraft") return "Accept alternative";
  }
  return meta.label;
}

export interface DecisionButtonsProps {
  /** Outgoing edge conditions — one button rendered per option. */
  options: readonly string[];
  /** Called when the operator submits a decision. */
  onSubmit: (decision: string) => void;
  /** Disable everything while a mutation is in-flight. */
  isPending: boolean;
  /** Current feedback text — used to gate "requires feedback" buttons. */
  feedback: string;
  /** Optional trigger context for label overrides (alternative_proposal etc). */
  trigger?: GateTrigger | null;
  /** Per-button test id prefix, e.g. "gate" yields data-testid="gate-approve-button". */
  testIdPrefix?: string;
}

/**
 * Renders one button per outgoing edge condition from the gate's template node.
 * Replaces the previous label-based branching that left the Approvals page
 * stuck on legacy approve/reject for v23 spec gates.
 */
export default function DecisionButtons({
  options,
  onSubmit,
  isPending,
  feedback,
  trigger = null,
  testIdPrefix,
}: DecisionButtonsProps) {
  const opts = options.length > 0 ? options : LEGACY_DECISION_OPTIONS;
  const isKnown = (o: string) => Object.prototype.hasOwnProperty.call(DECISION_META, o);

  return (
    <div className="flex flex-col gap-2 md:flex-row">
      {opts.map((option) => {
        const meta = metaFor(option);
        const disabled = isPending || (meta.requiresFeedback && feedback.trim().length === 0);
        const Icon = meta.icon;
        const testId = testIdPrefix
          ? `${testIdPrefix}-${slugForTestId(option)}-button`
          : undefined;

        const iconNode: ReactNode = isPending ? (
          <Loader2 className="mr-1.5 h-4 w-4 animate-spin" />
        ) : isKnown(option) ? (
          <Icon className="mr-1.5 h-4 w-4" />
        ) : null;

        return (
          <Button
            key={option}
            data-testid={testId}
            variant={meta.variant}
            className={meta.className ? `flex-1 ${meta.className}` : "flex-1"}
            disabled={disabled}
            onClick={() => onSubmit(option)}
            title={meta.title}
          >
            {iconNode}
            {labelFor(option, trigger)}
          </Button>
        );
      })}
    </div>
  );
}

function slugForTestId(option: string): string {
  // approved → approve, rejected → reject so existing data-testid="gate-approve-button"
  // and "gate-reject-button" selectors keep working. rereview/redraft pass through.
  if (option === "approved") return "approve";
  if (option === "rejected") return "reject";
  return option;
}
