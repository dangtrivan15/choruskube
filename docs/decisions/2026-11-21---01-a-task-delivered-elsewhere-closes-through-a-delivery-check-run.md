# A Task delivered elsewhere closes through a Delivery Check run

**Status:** current. Extends [2026-10-08---04](2026-10-08---04-task-closes-with-its-run.md) without amending it.

## Context

A Task closes only when its latest run finishes with every pull request it registered merged
([2026-10-08---04](2026-10-08---04-task-closes-with-its-run.md)). That rule exists for the Tasks
waiting on it: closing a Task unblocks them, and their runs start from the default branch, so a
Task marked done without its code there misleads every dependant.

Work does not always arrive through a Task's own run. A person may deliver it in a pull request
made outside ChorusKube, or alongside another Task. Such a Task had no way to close: there is no
`backlog → done` move, and completing an `in_progress` Task still needs a run. Marking it done on
a person's word alone is what the rule above exists to prevent, and checking the pull requests
needs GitHub, which the API server may not call
([2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md)).

## Decision

- **Closing a Task as delivered starts a Delivery Check run of it.**
  `POST /api/v1/tasks/{id}/delivery-check` takes one to twenty pull request URLs and a note. It is
  allowed from `backlog`, or from `in_progress` once the Task's latest run has ended, and refused
  while a run is live. Each URL must name a `github.com` pull request in a repository of the
  Task's software project, because the run's GitHub token reaches no other. The server registers
  the pull requests on the new run and moves the Task to `in_progress`. The note becomes the run's
  prompt, and an audit event records who closed it and with what.
- **The Task's blockers and its Story's design are not checked.** They decide when work may start,
  and this records work that already landed.
- **The run is the system template `delivery-check`: one gate that approves itself and verifies.**
  Its config sets `verify_pull_requests_merged` and `approve_on_arrival`:
  - `verify_pull_requests_merged: true` turns the gate's finalize into a verification. The plan
    sends each pull request with its repository's default branch, `verifyOnly: true` and no merge
    method. The Worker reads each one and refuses unless it is merged into that branch. It never
    compares a branch and never merges.
  - `approve_on_arrival: true` has the server approve the gate when the workflow asks for a held
    approval as the gate opens. Graph validation accepts it only beside
    `verify_pull_requests_merged`, and never beside `merge_pull_requests`, `materialize` or
    `drift_recheck`. An approval no person gave can therefore only confirm a merge that already
    happened.
- **The Task closes the way every Task closes.** A verified pull request is recorded as merged.
  When the gate completes, the run finishes and `TaskSettlementService` closes the Task. A refusal
  returns the gate to awaiting a decision with the reason shown on it. Approving it again
  re-verifies; cancelling the run leaves the Task `in_progress`, with its latest run ended.

## Why not the alternatives

- **Take the person's word.** Closing on a note alone is quick, but a wrong link or an unmerged
  pull request unblocks dependants onto a default branch that lacks the work.
- **A Worker check bound to the Task rather than to a run.** Every Worker GitHub call is scoped to
  a run three ways: placement picks a Worker per run, the token reaches the repositories one run
  spans, and the Worker's own requests to the API server are authorized per run. A Task-scoped
  call needs a new form of each, and so a wider credential boundary. A run already has all three.
- **A merge-on-approval gate pointed at the claimed pull requests.** It would merge any that were
  still open, which is not what closing as delivered means.

## Consequences

- A Delivery Check run appears in the Task's run history with no agent work. It is the record of
  the check.
- The rule "a Task is done when its latest run finished with every pull request merged" now
  applies to every Task, including ones delivered outside ChorusKube.
- Mixed versions during a rollout fail closed. A Worker without the verify-only mode sees no merge
  method and refuses the step; an orchestrator without it drops the flag, and the Worker then does
  the same. Neither merges anything.
- The template runs nothing on an agent pod, so a deployment's AI-credential preflight does not
  apply to it.
