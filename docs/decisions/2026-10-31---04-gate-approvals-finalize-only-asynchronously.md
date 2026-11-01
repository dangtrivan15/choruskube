# Every gate approval finalizes asynchronously; the synchronous path and its re-apply timer are deleted

**Status:** current

## Context

A gate approval can carry work beyond recording a decision: merging the run's pull requests,
re-checking against the current default branch, and writing a reviewer's roadmap. Two protocols
for doing that work existed side by side. The older one ran every step inside the reviewer's own
HTTP request, on the control plane, with the control plane's own code-host credential. The newer
one moved that work onto the workflow and the Worker: the workflow drives the approval step by
step, and the Worker makes the code-host calls with a credential it holds.

Both protocols had to keep working at once because a Temporal workflow replays its recorded
history rather than re-deciding it. A gate opened by the old protocol recorded a history that
expects the old handling on every later event, so the control plane kept the inline compare and
merge code reachable, and a timer kept re-applying approvals held by a gate on that path. The
workflow itself kept a version branch that opened a gate the old way, with a second branch that
did the same for any run that had no Worker task queue to send work to.

[2026-10-17---01](2026-10-17---01-worker-declares-credential-source-at-registration.md) states the
general rule this change applies: moving work onto the Worker is a hard cutoff, not a gradual
rollout. A path kept only so that runs recorded before a change can finish is deleted once those
runs have finished or been cancelled, not kept around indefinitely because something might still
be using it. By now every gate opened in the last several weeks already speaks the asynchronous
protocol, so the compatibility path serves only a shrinking, enumerable set of old histories, and
the cutoff deletes it.

## Decision

**Every gate the workflow opens is marked `async`.** Writing the per-gate protocol marker used to
depend on both finding the newer workflow version and the run having a Worker task queue;
otherwise the gate opened unmarked and fell back to the old protocol. Both conditions always hold
now, so the normal gate-open path always writes the marker.

**The `async-gate-finalize` version call stays, with 1 as its oldest supported version.** The
version check itself is not removed — only its branch that opened a gate the old way. Raising the
oldest supported version means a workflow history whose gate opened before the marker existed
fails at that call, by name, instead of drifting into whatever the rest of the code now assumes.

**A run with no Worker task queue is refused at start.** The workflow no longer treats an empty
queue as "use my own queue, since there is nothing else to send work to." A run cannot reach a
gate, or anything else, without a queue to run its steps on.

**The API refuses, with 409, an approval needing finalize work on an unmarked gate, and never
signals it without doing that work.** An unmarked gate can only be a straggler from before this
change, since nothing marks a gate any other way now. Finalize work means merging, re-checking or
writing a roadmap; a decision that needs none of that still signals normally even on an unmarked
gate.

**Held approvals are re-applied only by the workflow's own claim at gate open.** The ten-second
timer that used to re-apply a held approval for a gate on the old protocol is deleted along with
that protocol, and nothing replaces it, because the workflow already claims any approval still
held for a gate the moment that gate reopens — inside its own single event stream, where a
reviewer's click cannot race it.

**The approve response is `signaled` or `finalizing`.** The old `held_for_recheck` outcome and its
accompanying `staleRepos` list are gone from the response shape; a held approval is visible
through the gate's own approval record, which the reviewer already sees.

**The API's gate compare, pull-request read and merge are deleted, and the allowlist loses those
entries.** These were the control plane's last code-host calls made for a gate. Removing them
leaves every gate-related code-host call on the Worker, where it already runs under the
data-plane credential rule.

## Supersedes

- From [2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md): its per-gate choice
  of protocol through `finalize_mode`, the synchronous path it kept for a gate opened before it or
  by a run with no Worker task queue, and its plan to remove `GateStampReconciler` once no such gate
  could still be open.
- From [2026-10-11---01](2026-10-11---01-approval-rechecks-against-current-main.md): its in-request
  check and its timer-driven re-apply, for every gate now rather than only the ones already on the
  asynchronous path.
- From [2026-10-06---01](2026-10-06---01-merge-pull-requests-on-approval.md): its in-request
  merging, for every gate now rather than only the ones not already covered by a later entry.
- From [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md): its listing of the
  synchronous gate path among the legacy routes still left to remove.
- From [2026-10-15---01](2026-10-15---01-roadmap-issues-filed-on-the-worker.md): its claim that the
  one-step roadmap-write path continues to serve the synchronous gate protocol.
- From [2026-10-13---01](2026-10-13---01-branch-cleanup-on-the-worker.md): its rule that a run with
  no Worker task queue keeps its branches, because such a run is now refused before it starts and
  so never reaches branch cleanup at all.

## Consequences

- **Upgrade precondition.** Before upgrading, an operator must find and cancel any open run that
  either opened a gate the old way, or may have started before every run always carried a Worker
  task queue. Both queries below must return no rows. Cancel each run they list through the app,
  never by terminating its workflow directly in Temporal, so its row closes and its approvals void.

  ```sql
  -- 1. Open runs that ever opened a gate without the async marker
  SELECT DISTINCT r.id, r.status, r.created_at
  FROM workflow_run r
  JOIN node_execution e  ON e.workflow_run_id = r.id
  JOIN template_node tn  ON tn.id = e.template_node_id
  JOIN node_definition d ON d.id = tn.node_definition_id
  WHERE r.status IN ('pending','running','paused','awaiting_human','awaiting_retry')
    AND d.executor_type = 'human'
    AND e.finalize_mode IS NULL
    AND e.status NOT IN ('pending','skipped');

  -- 2. Open runs that may have started without a Worker task queue
  SELECT id, status, created_at
  FROM workflow_run
  WHERE status IN ('pending','running','paused','awaiting_human','awaiting_retry')
    AND created_at < :cutoff;
  ```

  The queue is not stored on the run, so query 2 cuts off by date: `:cutoff` is the end of the day
  the install first ran a build containing #77, the change after which the API sends a Worker
  task queue for every run. An install first deployed on a later build can skip query 2. Query 1
  can also list a run whose only unmarked gate was pre-decided by a retry after it timed out (its
  execution log reads "Human decision pre-applied"); keeping or cancelling it is equally safe.
- **A run the precondition missed blocks its workflow task after the upgrade.** A gate opened
  before the marker fails naming the change, because Temporal's own version-removed error names
  `async-gate-finalize`; a run with no Worker task queue fails instead with a generic
  non-determinism error, since nothing names that mismatch. Either way, the API refuses that run's
  finalize approvals with a message that says to cancel the run. Recovery is the same for both:
  cancel the run in the app first, which closes its row and voids its approvals, then terminate its
  workflow in Temporal, because a blocked workflow task can never process the cancel signal on its
  own.
- **Rollback is safe in both the orchestrator and the API server.** Rolling the orchestrator back
  is safe because the previous build still opens a gate by finding the same marker it has always
  branched on; it never had to stop writing one. Rolling the API server back is safe because its
  inline compare-and-merge path is reachable only for an unmarked gate, and no new gate opens
  unmarked, so the previous build's fallback simply finds nothing to do.
- **The parsed GitHub rate-limit hints on the API's code-host exceptions are now unread by
  anything.** This is harmless, not a bug — they also hang off the token mint, which this change
  does not touch.
- **There is still no replay-test fixture proving a pre-marker history actually fails this way.**
  The failure comes from the Temporal SDK's own version check; a unit test pins only the version
  range the workflow asks for, and the upgrade precondition is what guarantees no such history is
  left to replay.

## Rejected

- **Deleting the version call outright, instead of only its old-protocol branch.** A rollback would
  then wedge every run that opened a gate under the new build, because the previous orchestrator
  build still branches on finding a marker that would no longer exist to find.
- **Treating an unmarked gate as asynchronous rather than refusing its approval.** It would
  silently stick the approval in "finalizing" inside a workflow that can never drive it to
  completion.
- **Letting an empty Worker task queue fall through to the workflow's own queue.** It would fail
  late, at the run's first agent step, with a stub's generic error message instead of a named
  failure at the start of the run.
- **Keeping the re-apply timer as a backstop.** It is unnecessary: both terminal-status writes — the
  workflow's own status report and a user's cancel — already void live approvals in the same
  database transaction, so nothing is ever left for a timer to find.
