# The workflow paths kept for runs recorded before branch cleanup and issue filing moved to the Worker are deleted

**Status:** current

## Context

[2026-10-13---01](2026-10-13---01-branch-cleanup-on-the-worker.md) moved a completed run's branch
cleanup to the run's Worker, and [2026-10-15---01](2026-10-15---01-roadmap-issues-filed-on-the-worker.md)
moved roadmap-extension issue filing there. Each move put a Temporal version call in the workflow
— `branch-cleanup-on-worker` and `gate-issues-on-worker` — and kept the old path behind its
default-version branch, because a workflow replays its recorded history rather than re-deciding
it. Those old paths kept the API server making GitHub calls with its own credential: compare and
ref delete for cleanup, issue creation for the one-step gate completion, and the per-repository
token lookup issue creation used.

[2026-10-17---01](2026-10-17---01-worker-declares-credential-source-at-registration.md) set the
rule that such a move is a hard cutoff: a path kept only so earlier runs can finish is deleted once
they have. [2026-10-31---04](2026-10-31---04-gate-approvals-finalize-only-asynchronously.md)
applied it to the `async-gate-finalize` call. This entry applies it to the other two.

## Decision

**Both version calls stay, with 1 as their oldest supported version, and their default-version
branches are deleted.** A history recorded before a marker existed now fails at that call, by
name, through Temporal's own removed-support error, instead of silently taking the new path. New
runs still record both markers, so a rolled-back orchestrator can still replay them. The
`gate-issues-on-worker` call is still made only for a plan that writes a roadmap, because that is
the only place a history ever recorded it.

**`writesRoadmap` is required on a finalize plan.** The orchestrator's plan step fails, without
retrying, on a plan that omits it, and the gate node fails with it. Read as `false`, a missing
field would send an approved roadmap down the one-step completion, which no longer writes one.
Every API in service since the split already sends the field, always, so nothing older needs
tolerating, and the orchestrator and the API can roll out in either order.

**The one-step completion is for a gate that writes no roadmap, and it never writes one.** A
one-step completion for a gate whose approved roadmap has something to write and was never
materialized is refused with 409. It still records the completion's merges first, because a merge
made on GitHub stands whatever happens to the approval; only the decision is withheld, and no
roadmap row is written. A roadmap gate whose plan has nothing to write still completes in one
step with its note, and one whose roadmap an earlier attempt already claimed is never written again:
it completes with whatever note that attempt stored.

**The API server's GitHub calls left by those paths are deleted.** That is the deprecated
branch-cleanup route and the cleanup and token logic behind it, the compare, ref-delete and
issue-creation calls, the materializer's API-side filing mode, and the credential resolver's
per-repository token method with the per-repository token scope it used. The API's remaining
GitHub calls are broker calls: minting a token for a Worker that holds no credentials of its own,
and checking credentials it holds. Core's control-plane GitHub call allowlist keeps one entry, the
installation-token mint.

## Supersedes

- From [2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md): its roadmap write
  and issue filing inside complete, which 2026-10-15---01 had already moved off complete for every
  gate whose plan declares a roadmap write. Complete now never writes a roadmap.
- From [2026-10-13---01](2026-10-13---01-branch-cleanup-on-the-worker.md): its "To remove later"
  consequence. The legacy activity, its client call, the legacy route, its service method and the
  control plane's ref-delete call are removed here.
- From [2026-10-15---01](2026-10-15---01-roadmap-issues-filed-on-the-worker.md): its consequence
  that the one-step path is unchanged and still files from the API, and its rule that a plan which
  does not declare a roadmap write keeps the one-step completion. A plan must now declare it, and
  the one-step path writes no roadmap and files nothing.
- From [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md): its listing of the API
  server's remaining GitHub calls. Only the broker calls above remain.

## Consequences

- **Upgrade precondition.** Before upgrading, an operator must find every open run whose workflow
  may have been recorded before both markers existed, and end it. The query below must return no
  rows. `:cutoff` is the end of the day the install first ran a build containing
  [#197](https://github.com/dangtrivan15/choruskube/pull/197), the later of the two moves; an
  install first deployed on a later build can skip it. Cancel each run it lists through the app,
  so its row closes and its approvals void, then terminate its workflow in Temporal and re-run the
  query.

  ```sql
  SELECT id, status, created_at
  FROM workflow_run
  WHERE status NOT IN ('completed', 'failed', 'cancelled')
    AND created_at < :cutoff;
  ```

- **A run the precondition missed blocks its workflow task after the upgrade,** failing at the
  version call it reached, by name. A gate straggler is recovered as
  [2026-10-31---04](2026-10-31---04-gate-approvals-finalize-only-asynchronously.md) recovers one:
  cancel the run in the app, then terminate its workflow. A cleanup straggler's run is already
  `completed`, so only its workflow needs terminating; its branches are kept.
- **An orchestrator older than this change keeps working against the new API** once the
  precondition holds: every plan now carries `writesRoadmap`, so it takes the split path for each
  gate that writes a roadmap and never sends that gate a one-step completion.
- **Rollback is a revert of the change.** New histories record both markers, so the previous
  orchestrator replays them.
- **A build that implements `GitHubCredentialResolver` must drop its `getTokenForRepo` override**
  in the same release, since the method is gone from the interface.
- **There is still no replay-test fixture proving a pre-marker history fails this way.** Unit tests
  pin only the version range each call asks for; the precondition is what guarantees no such
  history is left to replay.

## Rejected

- **Deleting either version call outright.** A straggler would silently take the new path, and a
  rolled-back orchestrator could not replay a run recorded without the marker.
- **Completing a one-step completion of an unwritten roadmap as if the gate wrote none.** No run
  would fail on it, but the reviewer's approved Stories and Tasks would vanish without a trace.
- **Keeping an API-side roadmap write without issue filing** so stragglers still finish. It is a
  second write path the split protocol replaced, against a cutoff that allows no fallback.
- **Reading a missing `writesRoadmap` as `false`.** That is the straggler path again, now ending in
  the 409 above instead of failing at the plan step that can say why.
