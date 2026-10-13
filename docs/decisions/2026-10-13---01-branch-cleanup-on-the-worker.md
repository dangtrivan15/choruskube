# A completed run's branches are deleted by its Worker

**Status:** current

## Context

Once a run completes, the system deletes its per-repo run branch when it carries no work the
repository's main line lacks. The API server did this itself, comparing and deleting with its
own GitHub credential — the one control-plane GitHub call [2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md)
named as out of scope when it moved gate merges to the Worker. A deployment that serves several
Fleets authorizes a Worker per run; as long as the control plane deletes branches itself, it
needs write access to every such run's repository, not only the Worker that already holds it.

## Decision

**The API plans, the Worker calls GitHub.** For a completed run, the API answers a read-only
plan — each repo's id, `owner/repo` slug and default branch — with no GitHub call and no
credential resolved. The orchestrator carries that plan to an activity on the run's own Worker
task queue, which obtains the run's GitHub token from its own credential source, compares, and
deletes.

**The Worker derives the branch it may delete from the run it is bound to**, never from its
input, so a wrong plan entry can name the wrong owner/repo but never steer a delete at a
different branch. It deletes only when the compare reports the branch exactly zero commits
ahead of the default branch; an error, a 404, an ahead branch, or a malformed compare response
all keep the branch.

**A fault on one repo never stops the next.** Only a token failure or a cancelled context fails
the step as a whole, which Temporal retries; every other per-repo outcome is recorded and the
loop moves on, matching the API's own cleanup, which never retried a GitHub fault either.

**A run with no Worker task queue keeps its branches.** Falling back to the control-plane path
here, as an async gate falls back when it has no Worker to finalize with, would keep a
control-plane GitHub call reachable from every run instead of only from histories recorded
before the change — and cleanup is best-effort, so a kept branch is a safe outcome where a gate
has no safe equivalent.

**A `branch-cleanup-on-worker` Temporal version marker picks the path**, recorded the first time
a run's workflow reaches cleanup after this change. A history recorded before the marker keeps
calling the legacy control-plane route exactly as it did; the route and its service method stay,
documented as serving only those histories, until none is left open.

**Every Worker GitHub request now carries a fixed `User-Agent: choruskube-worker`.** The Worker
and the API server send the same bearer token under the `control-plane` credential source, so
the token cannot show which of them made a given call; the User-Agent can, and an end-to-end
test reads it back from the stub GitHub server's own request log to prove the Worker made the
call.

## Consequences

- **Deploy order is API, then Worker, then orchestrator.** A mis-ordered rollout fails safe: an
  orchestrator newer than the API gets a 404 from the plan route, and one newer than the Worker
  finds the cleanup activity unregistered. Either way the run's branches are kept and the failure
  is only logged, never retried later.
- **A Worker credential source must keep answering a token request for a run that has already
  completed.** The step runs after the run's status write, and a future rule that stopped
  authorizing a Worker for an ended run would not fail loudly — it would just keep every branch.
- **Rollback hazard:** rolling back the orchestrator while a workflow sits between recording the
  marker and finishing cleanup fails that workflow's replay on the old code. Its run is already
  `completed`, so the fix is to terminate that one workflow, not to roll back further.
- **Squash- and rebase-merged run branches are still kept**, unchanged from before this change:
  they carry their own commits, so the compare reports them ahead.
- **To remove later:** the legacy activity, its API client call, the legacy route, its service
  method, and the control plane's ref-delete call, once no workflow history recorded before the
  marker is still open.

## Rejected

- **A Worker-facing route for the repo list**, so the Worker asks the API itself instead of the
  plan travelling through the workflow. Rejected because it would widen what a Worker credential
  can read and add a second Worker-facing contract for the same shape the gate's plan step
  already establishes.
- **Building the repo list from the workflow's start-of-run snapshot**, avoiding a new API call.
  Rejected because the snapshot lacks each repo's default branch and would duplicate owner/repo
  parsing in Go, and a list frozen at run start could miss a repo added mid-run.
- **Letting the plan name the branch to delete.** Rejected because a wrong plan entry — for
  example a release branch level with main — would then compare at zero and be deleted; deriving
  the name from the run the Worker is bound to removes that risk entirely.
- **Falling back to the control-plane route when a run has no Worker task queue**, as the async
  gate does. Rejected because cleanup is best-effort and a kept branch is already its safe
  outcome, so keeping the control-plane path reachable from new-version histories as well would
  only delay removing it.
- **Retrying the whole step on a transient GitHub fault.** Rejected because a retry would re-walk
  every repo and misreport an already-deleted branch as not found, for more GitHub calls than the
  legacy path ever made.
