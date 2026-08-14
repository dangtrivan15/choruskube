import { AlertTriangle, Ban, HelpCircle, Lightbulb, ServerCrash } from "lucide-react";
import type { GateTrigger } from "@/lib/decisions";

/**
 * Renders the "why is this gate open" banner for a `GateTrigger`. Shared by
 * `HumanGatePanel` (legacy `need_human_decision:*` triggers on v23 gates) and
 * `EscalationGatePanel` (Supervisor `escalation.md` categories) — a single
 * implementation means a fix here covers both callers.
 *
 * The `switch` is exhaustive: the `default` branch assigns `trigger` to a
 * `never`-typed local, so adding a new `GateTrigger` kind without a matching
 * `case` above fails `tsc`, not just misrenders at runtime.
 */
export default function TriggerBanner({ trigger }: { trigger: GateTrigger }) {
  switch (trigger.kind) {
    case "approved":
      return null;

    case "review_conflict":
      return (
        <div className="flex gap-3 rounded-md border border-status-warning/40 bg-status-warning/10 p-3">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-status-warning" />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Review conflict detected</p>
            <p className="text-muted-foreground">
              The reviewer found that its current fix would contradict or reverse an earlier
              review decision. Compare the two below and decide how to proceed.
            </p>
          </div>
        </div>
      );

    case "uncertainty":
      return (
        <div className="flex gap-3 rounded-md border border-status-info/40 bg-status-info/10 p-3">
          <HelpCircle className="mt-0.5 h-4 w-4 shrink-0 text-status-info" />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Reviewer uncertain about fix</p>
            <p className="text-muted-foreground">
              The reviewer found a flaw but was not confident in the correct fix. Review the
              description below and provide direction.
            </p>
          </div>
        </div>
      );

    case "alternative_proposal":
      return (
        <div className="flex gap-3 rounded-md border border-status-accent/40 bg-status-accent/10 p-3">
          <Lightbulb className="mt-0.5 h-4 w-4 shrink-0 text-status-accent" />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Alternative design proposed</p>
            <p className="text-muted-foreground">
              The reviewer believes a fundamentally different approach is better. Compare the
              current spec with the alternative below and decide.
            </p>
          </div>
        </div>
      );

    case "environment":
      return (
        <div className="flex gap-3 rounded-md border border-status-error/40 bg-status-error/10 p-3">
          <ServerCrash className="mt-0.5 h-4 w-4 shrink-0 text-status-error" />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Environment issue</p>
            <p className="text-muted-foreground">
              The agent hit a problem with its environment (e.g. a wedged CI runner or
              unavailable infrastructure) that it could not resolve on its own. Review the
              details below and route the run onward.
            </p>
          </div>
        </div>
      );

    case "blocked_external":
      return (
        <div className="flex gap-3 rounded-md border border-status-error/40 bg-status-error/10 p-3">
          <Ban className="mt-0.5 h-4 w-4 shrink-0 text-status-error" />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Blocked on an external dependency</p>
            <p className="text-muted-foreground">
              The agent is blocked on something outside its control — an external service,
              credential, or approval. Review the details below and route the run onward.
            </p>
          </div>
        </div>
      );

    default: {
      const exhaustiveCheck: never = trigger;
      throw new Error(`Unhandled GateTrigger kind: ${JSON.stringify(exhaustiveCheck)}`);
    }
  }
}
