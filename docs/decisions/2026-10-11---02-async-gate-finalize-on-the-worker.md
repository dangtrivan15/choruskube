# A gate approval finalizes asynchronously: the workflow drives it, and the Worker does the GitHub steps

**Status:** current

## Context

Approving a gate that merges pull requests or re-checks against main did all of its work inside
one HTTP request: the drift compare, the pull request inspection, the merges and the roadmap
writes.

- **Every step added made the click slower**, and the click waited on GitHub.
- **A held approval raced a reviewer.** It waited up to 10 seconds for a timer to re-apply it.
  A reviewer who clicked the reopened gate in that window cancelled the held approval before
  their own decision was validated.
- **GitHub calls ran in the wrong plane.** The control plane decides; the data plane holds
  credentials and compute. A data plane run by a customer must be able to keep its credentials
  local, and every GitHub call made for a gate ran in the API server.

## Decision

```
reviewer approves
      |
      v
API: validate the roadmap (invalid -> 400, nothing claimed); gate AWAITING_HUMAN ->
     FINALIZING (one atomic update), record the approval, signal the workflow,
     reply "finalizing"
      |
      v   the workflow drives the rest, one event at a time
plan (API): validate the roadmap again; list the repos to compare and the PRs to merge
      |        roadmap invalid -> refuse     stale reason already known -> hold
      |        nothing to compare or merge -> complete, no Worker step
      v
GitHub steps (Worker): compare each base with main; if current, inspect every PR
      |                and merge in order, reading the gate before each merge
      |
      +-- merged ----------> complete (API): record merges, write the roadmap once,
      |                      the gate completes and the run continues
      +-- main moved ------> hold (API): write guidance, route to the re-check;
      |                      the approval is re-applied when the gate reopens
      +-- refused ---------> refuse (API): record merges already made, void the
                             approval, gate back to AWAITING_HUMAN with the reason
```

**Approval is asynchronous, through a `finalizing` status.** The approve request validates the
reviewer's roadmap document first, so an invalid edit is a 400 with nothing claimed and the edits
still on screen; a refusal after the claim would reopen the gate with the server's document. It
then makes one atomic `awaiting_human -> finalizing` update, records the approval, signals the
run's workflow and replies. A second click on a finalizing gate gets "already deciding", so two
clicks can never both start a finalize. Decisions that need no GitHub work (`rereview`, `redraft`,
`route:*`) and gates that declare nothing to finalize signal as final, as before.

**The workflow finalizes in steps; the API decides and writes, the Worker only calls GitHub.**
Every database write stays on the API, and results travel through the workflow. This follows
the split Temporal makes: the workflow is deterministic, side effects are activities, and the
data plane does the work that needs credentials. Finalizing runs inside the existing gate wait,
not in a second workflow, because the gate's own goroutine already runs activities after its
signal.

**The Worker reaches GitHub through a `CredentialProvider`.**
- *ControlPlane* asks the API per run (`GET /worker/runs/{runId}/github-token`), authorized by
  the Worker's pin to the run's Fleet. Workers serving more than one tenant use it, and so does
  the open-source Worker until its other GitHub calls move.
- *Local* reads a mounted folder (`CREDENTIALS_DIR`) on every request, so a rotated Secret applies
  without a restart. For one tenant's own data plane only. A GitHub App key never leaves the
  Worker, which mints and caches installation tokens itself.
- **The mode is declared with `CREDENTIAL_SOURCE`, never detected.** A Worker that fell back to
  the control plane on its own would hide a misconfiguration.

**Agent pods take their GitHub token from their Worker.** `github_token_url` in the pod's config
points at the Worker's callback server, which authenticates the pod's job secret and takes the
run from its own launch cache, never from the pod. This also ends the 401 a pod got by asking
before the API had its secret hash. The API's token route stays for pods an older Worker
launched.

**A refused finalize returns the gate to waiting, with the reason.** A merge GitHub refuses
(draft, conflict, branch protection), an invalid roadmap document the plan step catches, a Worker
step that fails after its retries or that no Worker picks up, and a held approval past its round
cap all void the approval with the reason and reopen the gate, and the gate's timeout restarts in
full. Each of these is something a person can fix, and failing the node would turn it into a
retry. Merges the Worker made before refusing are recorded before the approval is voided, because
a merge cannot be undone and an unrecorded one leaves the pull request unmerged here and its audit
event lost.

**An API step that fails fails the node.** Plan, hold, complete and refuse are the API's own
steps. One that still errors after its retries, or that the API rejects outright with a 4xx,
fails the gate's node instead of refusing, since refusing is itself an API step. The node's
`failed` status write then voids the approval the gate was finalizing, so a retried gate can be
approved again.

**A held approval is re-applied by the workflow, not by a timer.** When a gate opens, the
workflow claims a still-held approval before it waits for a signal. The claim is one atomic step
inside the workflow's single event stream, so no window exists for a reviewer to race it. A
reviewer's non-approve decision claims the gate, then voids a held approval in a transaction of its
own before it signals; if the signal fails, the gate is released but the held approval stays
voided. A run's end voids its live approvals, and a gate that fails while finalizing releases its
approval, so a retried gate can be approved again instead of hitting the one-live-approval-per-gate
constraint.

**The protocol is chosen per gate, with `finalize_mode`.** A gate opened by the new workflow code
writes `async` on its node execution; a gate opened before the deploy has null, and its workflow
history waits for a final decision signal, so it keeps the synchronous path and
`GateStampReconciler`. The new workflow code is behind a version marker for the same reason. A run
with no Worker task queue also opens its gates as null, since nothing could run its GitHub step.

**The roadmap is written at most once per approval.** Under the row lock, finalizing sets
`materialized_at` and commits; it then writes the roadmap with no surrounding transaction and
stores the resulting note, which a retry reuses. Writing inside the transaction was rejected: the
per-item skips the synchronous path designs for turned into failures that every retry repeated,
each re-filing GitHub issues. The cost is accepted: a crash between the claim and the note leaves
a partial roadmap that is never retried, as it could on the synchronous path.

**The re-check fails closed.** The Worker compares each repo's recorded base with its branch;
ahead, or a 404, is stale. It skips the compare only for a repo whose run pull requests have all
merged, because main already carries them and nothing is left to protect there. A repo with any
unmerged pull request is always compared; a compare polluted by the run's own earlier merges
reads stale, so the cost is one extra re-check round, never an unchecked merge. A retry after a
Worker was killed mid-merge resumes from its heartbeat rather than comparing against its own
merges.

**A merge happens only while the gate is finalizing.** The Worker reads the gate's execution
before every merge and stops the step, without retry, once it has left `finalizing`, as a
cancelled run's gate does. A cancel otherwise reaches the step only on a throttled heartbeat, up to
about 48 seconds later. A read that fails is retried; nothing merges without it.

**Retry rules follow what a retry can change.** An API answer of 400, 404 or 409 repeats
identically, so it is not retried; 5xx and network errors are. GitHub's refusals are a result,
never an error, so they are never retried. Completing gets a 5 minute budget where the other API
steps get 60 seconds, because writing a large roadmap files many issues, and an overlapping retry
would complete the gate without the roadmap note. A Worker step no Worker picks up is bounded, so a
missing Worker refuses the approval instead of leaving the gate finalizing; a plan with nothing to
compare or merge skips the Worker step altogether. The refusal reason a reviewer sees is GitHub's
scrubbed message or a fixed text, never a raw error chain that could carry an internal URL.

## Supersedes

- From [2026-10-06---01](2026-10-06---01-merge-pull-requests-on-approval.md), the rule that
  merging runs inside the approval request itself, for every gate opened by workflow code that
  finalizes approvals itself. Its merge rules carry over unchanged and now run on the Worker:
  inspect before merging, refuse the whole approval on a draft or conflict, merge in order pinned
  to the inspected head, skip a closed pull request, scrub the refusal text. The roadmap proposal
  is still validated before anything merges and written after.
- From [2026-10-11---01](2026-10-11---01-approval-rechecks-against-current-main.md), the in-request
  check and the timer-driven re-apply by `GateStampReconciler`, for the same gates. What the check
  compares, the round cap, and what voids a held approval hold as written there.

## Consequences

- **Deploy order is API, then Worker, then orchestrator.** The API understands both protocols
  before any gate speaks `async`, and the Worker registers the GitHub step before the orchestrator
  schedules it. Gates open at deploy finish on the synchronous path.
- **The Worker has new settings:** `CREDENTIAL_SOURCE` (default `control-plane`),
  `CREDENTIALS_DIR` and `GITHUB_API_URL`, which the test stacks point at a GitHub stub. A Worker
  serving more than one tenant must stay on `control-plane`.
- **The Worker now holds GitHub write power for merges.** Under ControlPlane that is a token the
  API already hands to agent pods. Under Local it is the customer's own.
- **The approval record keeps the name `gate_stamp`** though it now starts at the click and
  covers every approval.
- **Known gap: merges around a cancel are not recorded.** After a cancel the Worker merges at
  most the pull request it is merging at that moment. The step's result is discarded with the
  cancelled workflow, so neither that merge nor any the step made before the cancel is recorded
  on the cancelled run, though GitHub shows them merged.
- **An approval needs a live Worker within 10 minutes.** A GitHub step no Worker picks up within
  that bound refuses the approval with a reason saying so. A gate with nothing to compare or merge
  does not wait on a Worker.
- **Runs without a Worker task queue keep the synchronous path.** A run started before Worker task
  queues existed opens its gates with null `finalize_mode`, so it approves as before the change.
- **Known gap: no replay fixture.** Old gate histories were replayed against the new workflow
  code locally, but no recorded history is committed, so the repo has no test that replays one.
- **To remove later:** `GateStampReconciler` once no gate with null `finalize_mode` can be open,
  and the API's pod token route once no older Worker launches pods.
- **Out of scope:** the other control-plane GitHub calls (branch cleanup, issue filing and
  closing, credential health checks) and scoping tokens to a run's repos. They move later, and
  the open-source Worker stays on ControlPlane until they do.

## Rejected

- **Keep approval synchronous and re-apply held approvals right after the gate opens.** The
  GitHub calls stay in the control plane and the click still waits on GitHub.
- **Finalize on the API or the orchestrator.** It contradicts the data-plane credential intent.
- **A separate workflow per finalize.** More moving parts, with nothing the gate's own goroutine
  cannot already do.
- **Fail the node on a refusal.** It turns a fixable refusal into a retry instead of a return to
  review.
- **Detect the credential mode.** A silent fallback hides a misconfiguration.
