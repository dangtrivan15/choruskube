# Approve Spec & Plan re-applies a held approval after Spec Review revises the spec

**Status:** current

## Context

Under [2026-10-11---01](2026-10-11---01-approval-rechecks-against-current-main.md), approving a
gate after the default branch moved holds the approval, routes the gate's `rereview` back to the
review node, and re-applies the held approval when the gate is reached again. The gate's `stamp`
mode decides what a revision on the way back does: `always` re-applies regardless, `if_unchanged`
voids the approval once the routed-to node emits `revised`, and the run waits on a human again.

Feature Dev declared `always` at Final Approval and `if_unchanged` at Approve Spec & Plan. At the
spec gate that voided about a third of re-checks, whatever the revision was: a renumbered
migration bounced to the human exactly as a changed approach did. The strictness sat at the wrong
gate:

- Approve Spec & Plan is followed by another human checkpoint, Final Approval, and nothing merges
  before it.
- Final Approval merges, which cannot be undone, and already trusts `always`.

## Decision

**Approve Spec & Plan declares `drift_recheck: {route: rereview, stamp: always}`, as Final
Approval does.** During a re-check Spec Review reconciles the spec with what merged, and the run
proceeds to Implement on the held approval, whether or not Spec Review revised the spec.

**Spec Review escalating is the way back to a human.** Its "When to escalate" section already
covers drift that would change the spec's decomposition or architecture, or reverse a decision a
merged run made. Escalating visits the Supervisor, which voids the held approval.

**Spec Review is told its approval stands in for the human's.** When its `human_guidance.md` opens
with the re-check heading (`GateDriftCheckService.GUIDANCE_HEADING`), the prompt says the approval
re-applies to whatever Spec Review approves without a human seeing it again, so it changes only
what reconciling with the merged work requires. `FeatureDevPromptConventionsTest` ties the prompt
to that heading, so renaming it cannot silently stop Spec Review recognizing a re-check.

**The engine is unchanged.** `if_unchanged` remains a template feature with the rules in
2026-10-11---01; Feature Dev no longer uses it. A run on a template version before v52 keeps the
stamp mode its snapshot declared.

## Rejected

- **Keep `if_unchanged` at the spec gate.** Every reconciliation edit bounces to the human,
  mechanical ones included, who re-reads a spec they already approved to confirm it. Telling a
  substantive reconciliation from a mechanical one is the review agent's judgement, and escalation
  already carries it.
