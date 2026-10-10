# Approving a merge-declaring gate merges the run's registered pull requests synchronously, before any decision state is written

**Status:** current

## Context

Approving a Feature Development run's Final Approval gate ended the run but left every
registered pull request for a human to merge by hand. The approval request already
performs one non-idempotent side effect on `approved` — materializing a roadmap
proposal — inside the same claim/signal window described in
[2026-09-30---01](2026-09-30---01-roadmap-extension-proposals.md); merging needed to
fit the same shape without letting a merge failure leave roadmap items duplicated, or
a decision persisted while pull requests sit unmerged.

## Decision

**Merging runs inside the approval request itself, after the node's claim and before
anything about the decision is persisted** — the claim is a transient lock every
failure path releases, so it is not decision state, and a log entry naming what an
attempt actually merged is not either, since a merge on GitHub cannot be undone. A
dedicated workflow node, or a background reconciler, were both rejected: either gives
the reviewer no immediate feedback, and both would have the approval persisted before
a merge could fail.

**Whether a gate merges is a new config key, `merge_pull_requests: <method>`, validated
at run start and opted into by template version** — not inferred from a node's label or
a global flag. This follows the precedent of the existing roadmap-materialization gate
config: approval-time behavior is a mode a template declares, not something a node's
executor type or name implies. Graph validation rejects an unknown method, and the key
on a node that has no `approved` decision to fire it on; it cannot validate the key
against an executor type, because graph validation sees template nodes and edges, not
node definitions.

**Idempotency comes from inspecting GitHub's own state before merging, not from a
separate ledger.** Every pull request still unmerged in the local database is read
from the host first: already merged or closed-without-merging rows are skipped, and a
draft or conflicting row refuses the whole approval before anything is merged. Only
then are the remaining rows merged, in registration order, each pinned to the head
commit seen during inspection, stopping at the first refusal. A row the database
already records as merged needs no read at all — a merge cannot be undone, so that
row's answer can never change. This means a retry after every row is already merged
touches neither the host nor a credential, which keeps approving an already-materialized
run safe even when no credential is configured at all.

**Merging runs after the roadmap proposal is validated and before it is
materialized.** Materialization writes rows and is not idempotent, so if it ran first,
a merge failure followed by a retry would create the roadmap items twice; if the
proposal were validated only after merging, a rejected proposal would leave pull
requests merged while the gate is still open. Splitting validation from the write puts
every step that is likely to fail, or that is visible outside the system, ahead of the
first write a retry cannot safely repeat. The pre-existing window remains: a retry
after materialization succeeded but the workflow signal failed can materialize again.

**A closed-but-unmerged pull request is skipped with a note, not treated as a
blocker.** A human closed it deliberately; refusing approval until it is reopened
would trap the gate on an intentional decision. The cost is accepted: the run's
tracked unit of work does not close automatically, since closure requires every pull
request to be merged.

**A merge failure returns one error response summarizing the attempt** — what merged,
what blocked and why, and that the gate is still open — rather than a bare failure
code or a generic validation-style list. The reason text is the upstream host's own
message only, length-capped and scrubbed of the credential, because a fixed mapping
from status to text would lose guidance specific to the failure, and the full response
body is never safe to surface (some error payloads echo the request, including the
credential).

**Whoever may approve a gate today may trigger the merge; no new permission check is
added.** The same credential already lands code when an agent pushes a branch and
opens a pull request earlier in the same run, so requiring new authority at approval
time would not shrink what that credential can already do — it would only block people
from finishing runs they can already drive to this point. Branch protection on the
pull request's own host stays the real guard. Every attempt that merges at least one
pull request is audited — including one that is then refused on a later pull request —
because the audit event is the only durable record of which attempt performed an
irreversible action; recording it only on full success would leave a merge performed
by a refused (or later re-reviewed) attempt unaudited.

## Consequences

- A gate without the key is unaffected: approval persists the decision exactly as
  before, and the merge service is never called.
- Runs already waiting at a gate on an older template version keep today's
  behavior — merging is opt-in per template version, not retroactive.
- Cross-repo merge order is simply registration order; there is no mechanism to
  declare that one repo's pull request must merge before another's. An
  order-sensitive case is handled by merging that one pull request by hand first,
  which approval then skips.
- A pull request merged by an attempt that later fails, or by an attempt the
  reviewer chooses not to continue from, stays merged — merges are never reverted.
  The audit event and the error response are what tell a reviewer what already
  happened before they retry or choose a different path.
- A process crash between the claim and the signal strands the gate exactly as a
  roadmap-only approval already could; merging widens that window by the time the
  external calls take, but does not introduce a new failure mode.
