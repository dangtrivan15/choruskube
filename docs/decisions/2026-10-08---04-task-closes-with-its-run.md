# A Task closes in the transaction that finishes its run; nothing polls GitHub for merges

**Status:** current. Builds on [2026-10-06---01](2026-10-06---01-merge-pull-requests-on-approval.md) without amending
it.

## Context

`PullRequestStateReconciler` read every unmerged registered pull request from GitHub every
two minutes. It recorded merges, closed Tasks whose latest run had everything merged, and
disengaged the Autopilot when GitHub could not be read. That made sense while a human merged
by hand and nothing else learned of it.

[2026-10-06---01](2026-10-06---01-merge-pull-requests-on-approval.md) changed that. Approving a
gate that declares `merge_pull_requests` now merges the run's pull requests and records
`merged_at` itself. After that, everything the poll read in a live installation was a closed
pull request from a cancelled run whose Task a newer run had already closed:

- such a row can never merge;
- a successful read made it due again immediately, so it was read forever, at two GitHub calls
  each (a token mint and a read);
- those reads were what met GitHub's transient timeouts.

A mint timeout reached the fault classifier wrapped as "no GitHub credential", so the Autopilot
disengaged repeatedly over a minute of unreachable GitHub. It never received a 429 or any
rate-limit response.

## Decision

- **Closing a Task is part of finishing its run.**
  [`TaskSettlementService`](../../api-server/src/main/java/com/choruskube/core/service/TaskSettlementService.java)
  marks the Task done inside the transaction that moves the run to a terminal status. It is
  called from both places that do that: the orchestrator's status report and a human's cancel.
  `Propagation.MANDATORY` refuses any caller without a transaction.
- **The condition is the one closing always used.** The run is the Task's latest run, the Task is
  `in_progress`, and the run registered at least one pull request, every one of them merged. A
  call that finds anything else does nothing, which makes repeated reports idempotent.
- **"Not settled" is a return value, never an exception.** An exception crossing the
  transactional proxy marks the caller's transaction rollback-only even if caught, so it would
  undo the run's finish over a Task that merely was not ready.
- **External effects wait for the commit.** The linked GitHub issue closes after commit, in its
  own transaction, and the run-status STOMP event is published after commit. A rolled-back finish
  announces nothing and closes nothing. A failure closing the Task rolls the finish back, and the
  orchestrator retries its report.
- **The poll is deleted outright**, together with everything that existed only to serve it:
  - the backoff;
  - the quarantine and its Autopilot panel reason;
  - `AutopilotSafetyValve`, whose only caller it was;
  - `run_pull_request`'s scheduling fields.

## Why not the alternatives

- **Keep the poll, filtered to rows whose merge could still close a Task, and classify mint
  timeouts as transient.** That fixes the symptom, but it keeps a GitHub dependency on a path
  that Final Approval already covers, plus a failure mode that can stop the Autopilot.
- **Close on run finish, and keep a database-only sweep for Tasks left open.** A sweep exists to
  repair a missed close. Putting the close in the finish's own transaction leaves nothing to miss:
  either both commit, or neither does and the orchestrator retries.
- **Close the Task at approval time.** The run is not terminal yet at approval, and closing
  requires a terminal latest run. Approval would also have to know which node finishes the run.

## Consequences

- A pull request merged by hand outside Final Approval no longer closes its Task, and its
  recorded state no longer refreshes. Close such a Task by hand. This covers templates without
  `merge_pull_requests`, and cancelling a run and then merging its pull request.
- The Autopilot no longer stops itself over GitHub reachability. A broken credential shows up as
  failing runs instead, which the existing three-strike breaker stops.
- `signalHumanDecision` must stay non-transactional. Its merge record commits before the signal
  only because nothing encloses them, and a run that finishes before its merges are visible
  leaves its Task open with nothing to close it.
- The `run_pull_request` scheduling and quarantine columns, with their indexes, stay in the
  schema unread, so the previous image can still be rolled back to. Dropping them is a later
  forward-only migration.
- `AutopilotResolver.forResource` lost its only caller. Implementations outside this repository
  override it, so its removal is coordinated with them separately.
