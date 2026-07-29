export type GateTrigger =
  | { kind: "approved" }
  | { kind: "review_conflict" }
  | { kind: "uncertainty" }
  | { kind: "alternative_proposal" };

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
