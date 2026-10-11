# Approving a stale run holds the approval, re-checks it against current main, and re-applies it

**Status:** current; its in-request check and its timer-driven re-apply by `GateStampReconciler` are superseded, for gates opened by workflow code that finalizes approvals itself, by [2026-10-11---02-async-gate-finalize-on-the-worker](2026-10-11---02-async-gate-finalize-on-the-worker.md)

## Context

Each run is tested against the default branch it saw when its last node ran. When several runs on
the same repo are open at once, the first to merge moves main under all the others, so what they
tested no longer matches what they would merge into.

- **Text conflicts were caught late.** Merge-on-approval refuses a conflicting pull request, but
  only after a reviewer has already spent a review on a run that cannot merge.
- **Semantic conflicts were not caught at all.** Two runs each add the next numbered migration, or
  main renames a function a branch still calls. Git merges cleanly and main breaks.

Final Approval is meant to be a quick human stamp, so catching semantic drift cannot be the
reviewer's job.

## Decision

**The check runs only when a reviewer approves a gate that declares
`drift_recheck: {route, stamp}`.** Nothing fires when other runs merge. An eager re-check on every
merge would cost one re-check per waiting run per merge, and would still need this check, because
main can move again afterwards.

**Each agent pod records which default-branch commit its clone sat on, per repo.**
- The entrypoint rebases the run branch onto the clone's own default branch.
- It posts the outcome (`clean` with the base commit, `conflict` with the conflicted files, or
  `unavailable`) through `POST /internal/runs/{runId}/node-executions/{nodeExecId}/repo-bases`.
- The completion callback was rejected for this: it crosses several hops and drops everything but
  status and error for a failed node.

**At approval, every repo the run cloned is checked against the gate's predecessor's records.**
- A repo is stale if any of these is true:
  - main has any commit past the recorded base;
  - its record is a conflict or an unavailable fetch;
  - it has no record;
  - its base is gone from main's history.
- A missing record counts as stale. That costs one re-check, never a merge nobody tested, and it
  heals by itself when the agent image and the API server are briefly on different versions.
- The check is strict: an unrelated commit triggers a re-check too.

**A stale approval is held, not applied.**
- It is stored as a `gate_stamp` together with the reviewer's feedback and the roadmap document
  they approved.
- The gate fires its `route` decision with a system-written `human_guidance.md`. The guidance names
  the stale repos and the pull requests other runs merged since.
- `GateStampReconciler` re-applies the held approval when the gate awaits a decision again, inside
  `RunScopeBinder`. That approval goes through the same node-execution claim as a reviewer, so a
  concurrent click cannot decide the gate twice.
- A held approval materializes the roadmap document the reviewer approved, never a newer copy the
  re-check wrote.

**A held approval is voided, and the gate is left to a human, when any of these happens:**
- main moves again after 2 re-check rounds;
- after a re-check, it is still not known which commit was tested, because the pods record nothing
  or a repo's base is missing from main. A further round could not fix that, so the reason says to
  rebuild the agent image or fix the default-branch setting;
- the run visits the Supervisor;
- under `stamp: if_unchanged`, the routed-to node revises what was approved;
- applying it fails;
- the run ends.

A reviewer's own decision at the gate replaces it.

**The review agents judge semantic drift, not the reviewer.** Spec Review and Code Review escalate
with `review_conflict` when reconciling with what merged would change the approved decomposition
or architecture, or reverse a merged run's decision. The escalation categories are unchanged.

**Every push uses `--force-with-lease`.** Once the entrypoint has rebased a branch that is already
on origin, a plain push is rejected.

**Work done on a run's behalf with no request behind it runs inside `RunScopeBinder`.** Core binds
nothing. A downstream implementation that reads tenant state from the request binds the run's
organization there, so authorization, artifact reads, roadmap writes and audit all resolve it.

**The recorded base is self-reported by the run's own pod**, and its job secret scopes it to that
execution. It guards against a stale merge, not against a pod that misreports. The pod already
writes the code being merged.

## Supersedes

From [2026-10-06---01](2026-10-06---01-merge-pull-requests-on-approval.md), the rule that merging
runs inside the approval request itself, for gates declaring `drift_recheck`. Approving a stale
gate now holds the approval, and the system completes it later. The reviewer still gets an
immediate answer, because the approve response says the approval is held and why. The merge itself
still happens in a claimed gate decision, before anything about the decision is persisted.

## Rejected

- **Re-check every waiting run whenever a run merges.** It costs more and still needs the check at
  approval.
- **Route only on text conflicts.** That misses the semantic conflicts this exists for.
- **A per-repo queue around re-checks.** The two-round cap bounds the cost without locks or
  lock-ordering across multi-repo runs.
- **A new escalation category for drift.** It adds no routing, and would change the entrypoint,
  web UI and mock agent.
- **Removing the "spec is already approved" guidance from Code Review.** Code Review would then
  re-litigate approved designs. It was softened to the one case where something outside the run
  invalidated the spec.
