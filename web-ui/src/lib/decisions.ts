export type GateTrigger =
  | { kind: "approved" }
  | { kind: "review_conflict" }
  | { kind: "uncertainty" }
  | { kind: "alternative_proposal" }
  | { kind: "environment" }
  | { kind: "blocked_external" };

/**
 * Parse the predecessor reviewer's decision string into a normalized trigger
 * for the human gate UI. The reviewer emits one of: `approved`, `revised`,
 * `need_human_decision:review_conflict`, `need_human_decision:uncertainty`, or
 * `need_human_decision:alternative_proposal`. The gate only sees decisions
 * that route to it, so `revised` (self-loop) is not a valid input here.
 *
 * Backwards-compat: bare `need_human_decision` (v22) collapses to
 * `uncertainty` since it's the closest semantic equivalent.
 */
export function parseGateTrigger(decision: string | null | undefined): GateTrigger {
  if (!decision || decision === "approved") return { kind: "approved" };
  if (decision === "need_human_decision:review_conflict") return { kind: "review_conflict" };
  if (decision === "need_human_decision:uncertainty") return { kind: "uncertainty" };
  if (decision === "need_human_decision:alternative_proposal") return { kind: "alternative_proposal" };
  if (decision === "need_human_decision") return { kind: "uncertainty" };
  return { kind: "approved" };
}

const ESCALATION_CATEGORIES = [
  "review_conflict",
  "uncertainty",
  "alternative_proposal",
  "environment",
  "blocked_external",
] as const;

/**
 * Map an escalation.md `category` (parsed server-side into EscalationContext) to a banner
 * trigger. Unknown or absent degrades to `approved`, which renders no banner — the front
 * matter is agent-authored, so it must never break the gate.
 *
 * Distinct from `parseGateTrigger`, which reads the pre-v37 `need_human_decision:*` decision
 * strings still present in frozen older runs.
 */
export function parseEscalationCategory(category: string | null | undefined): GateTrigger {
  if (category && (ESCALATION_CATEGORIES as readonly string[]).includes(category)) {
    return { kind: category } as GateTrigger;
  }
  return { kind: "approved" };
}
