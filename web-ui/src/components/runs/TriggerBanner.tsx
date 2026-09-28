import { AlertTriangle, Ban, HelpCircle, Lightbulb, ServerCrash } from "lucide-react";
import type { GateTrigger } from "@/lib/decisions";
import { STATUS_TONE_CLASSES } from "@/lib/statusColors";
import { cn } from "@/lib/utils";

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
        <div className={cn("flex gap-3 rounded-md p-3 border", STATUS_TONE_CLASSES.warning.callout)}>
          <AlertTriangle className={cn("mt-0.5 h-4 w-4 shrink-0", STATUS_TONE_CLASSES.warning.text)} />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Review conflict detected</p>
            <p className="text-foreground">
              The reviewer found that its current fix would contradict or reverse an earlier
              review decision. Compare the two below and decide how to proceed.
            </p>
          </div>
        </div>
      );

    case "uncertainty":
      return (
        <div className={cn("flex gap-3 rounded-md p-3 border", STATUS_TONE_CLASSES.info.callout)}>
          <HelpCircle className={cn("mt-0.5 h-4 w-4 shrink-0", STATUS_TONE_CLASSES.info.text)} />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Reviewer uncertain about fix</p>
            <p className="text-foreground">
              The reviewer found a flaw but was not confident in the correct fix. Review the
              description below and provide direction.
            </p>
          </div>
        </div>
      );

    case "alternative_proposal":
      return (
        <div className={cn("flex gap-3 rounded-md p-3 border", STATUS_TONE_CLASSES.accent.callout)}>
          <Lightbulb className={cn("mt-0.5 h-4 w-4 shrink-0", STATUS_TONE_CLASSES.accent.text)} />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Alternative design proposed</p>
            <p className="text-foreground">
              The reviewer believes a fundamentally different approach is better. Compare the
              current spec with the alternative below and decide.
            </p>
          </div>
        </div>
      );

    case "environment":
      return (
        <div className={cn("flex gap-3 rounded-md p-3 border", STATUS_TONE_CLASSES.error.callout)}>
          <ServerCrash className={cn("mt-0.5 h-4 w-4 shrink-0", STATUS_TONE_CLASSES.error.text)} />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Environment issue</p>
            <p className="text-foreground">
              The agent hit a problem with its environment (e.g. a wedged CI runner or
              unavailable infrastructure) that it could not resolve on its own. Review the
              details below and route the run onward.
            </p>
          </div>
        </div>
      );

    case "blocked_external":
      return (
        <div className={cn("flex gap-3 rounded-md p-3 border", STATUS_TONE_CLASSES.error.callout)}>
          <Ban className={cn("mt-0.5 h-4 w-4 shrink-0", STATUS_TONE_CLASSES.error.text)} />
          <div className="space-y-0.5 text-sm">
            <p className="font-semibold">Blocked on an external dependency</p>
            <p className="text-foreground">
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
