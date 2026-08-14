import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { Separator } from "@/components/ui/separator";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import ArtifactBrowser from "./ArtifactBrowser";
import ReviewHistory from "./ReviewHistory";
import TriggerBanner from "./TriggerBanner";
import { parseEscalationCategory } from "@/lib/decisions";
import type { EscalationContext } from "@/lib/types";

export interface EscalationGatePanelProps {
  runId: string;
  /**
   * Why this run was escalated to the Supervisor. `null`/`undefined` when nothing
   * has escalated yet, or when the category/summary front matter was unreadable —
   * either way the picker below must stay usable.
   */
  escalation?: EscalationContext | null;
  /** The Supervisor's outgoing `route:<label>` options — one per candidate target node. */
  decisionOptions: readonly string[];
  guidance: string;
  onGuidanceChange: (value: string) => void;
  onConfirm: (decision: string) => void;
  isPending: boolean;
  /** Hides the guidance textarea, target picker, and confirm button for read-only viewers. */
  readOnly?: boolean;
}

/** `route:qa_review` → `Qa Review`. Mirrors DecisionButtons' fallback label formatting. */
function targetLabel(option: string): string {
  const raw = option.startsWith("route:") ? option.slice("route:".length) : option;
  return raw
    .split(/[_\s-]/)
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1).toLowerCase())
    .join(" ");
}

/**
 * Renders a Supervisor escalation gate: why the run was escalated (category banner +
 * escalator context/artifacts/review-history) and a target picker to route the run
 * onward. Replaces the normal predecessor-output + one-button-per-option flow that
 * `HumanGatePanel`/`GateCard` use for ordinary gates — the Supervisor has no inbound
 * edges (nothing to show as "previous step output") and can have far more outgoing
 * targets than fit as buttons.
 */
export default function EscalationGatePanel({
  runId,
  escalation,
  decisionOptions,
  guidance,
  onGuidanceChange,
  onConfirm,
  isPending,
  readOnly = false,
}: EscalationGatePanelProps) {
  const [selectedTarget, setSelectedTarget] = useState<string | null>(null);
  const trigger = parseEscalationCategory(escalation?.category ?? null);

  return (
    <div className="space-y-4" data-testid="escalation-gate-panel">
      <TriggerBanner trigger={trigger} />

      <div className="space-y-1 text-sm">
        <p>
          <span className="text-muted-foreground">Escalated by </span>
          <span className="font-semibold">{escalation?.escalatorLabel ?? "Unknown node"}</span>
        </p>
        {escalation?.summary ? (
          <p className="text-muted-foreground">{escalation.summary}</p>
        ) : (
          <p className="italic text-muted-foreground">No summary available.</p>
        )}
      </div>

      {escalation?.escalatorExecId && (
        <ArtifactBrowser runId={runId} execId={escalation.escalatorExecId} />
      )}

      <ReviewHistory runId={runId} loopGroup={escalation?.escalatorLoopGroup ?? null} />

      {!readOnly && (
        <>
          <Separator />

          <div className="space-y-2">
            <label
              htmlFor="escalation-guidance"
              className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
            >
              Guidance
            </label>
            <Textarea
              id="escalation-guidance"
              data-testid="escalation-guidance-input"
              placeholder="Explain how the run should proceed..."
              value={guidance}
              onChange={(e) => onGuidanceChange(e.target.value)}
              disabled={isPending}
            />
          </div>

          <div className="space-y-2">
            <label className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              Route to
            </label>
            <Select
              value={selectedTarget}
              onValueChange={(value) => setSelectedTarget(value as string | null)}
            >
              <SelectTrigger data-testid="escalation-target-picker" className="w-full">
                <SelectValue placeholder="Choose a target node..." />
              </SelectTrigger>
              <SelectContent>
                {decisionOptions.map((option) => (
                  <SelectItem
                    key={option}
                    value={option}
                    data-testid={`escalation-target-option-${option.slice("route:".length)}`}
                  >
                    {targetLabel(option)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <Button
            data-testid="escalation-confirm-button"
            disabled={isPending || selectedTarget == null}
            onClick={() => {
              if (selectedTarget) onConfirm(selectedTarget);
            }}
            className="w-full"
          >
            Confirm routing
          </Button>
        </>
      )}
    </div>
  );
}
