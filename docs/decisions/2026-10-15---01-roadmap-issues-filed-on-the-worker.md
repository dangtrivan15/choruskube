# Roadmap-extension issues are filed by the run's Worker during gate finalize, not inline with the roadmap write

**Status:** current; its claim that the one-step complete is kept for an older orchestrator is superseded by [2026-10-17---01-worker-declares-credential-source-at-registration](2026-10-17---01-worker-declares-credential-source-at-registration.md); its claim that the one-step path continues to serve the synchronous gate protocol is superseded by [2026-10-31---04-gate-approvals-finalize-only-asynchronously](2026-10-31---04-gate-approvals-finalize-only-asynchronously.md)

## Context

Approving a `roadmap_extension` gate writes the reviewer's new Stories and Tasks to the roadmap
and, for each new Task, files a matching GitHub issue so the work shows up where the team tracks
it. The write and the GitHub call happened together, inside the control plane's one claimed step,
using a credential the control plane held.

[2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md) moved a gate's re-check
and merges onto the Worker on the same reasoning:
[2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) states it as a rule — the control
plane decides and writes its database; the data plane holds credentials and makes the calls that
need them. Issue filing did not move with them. It stayed in the control plane's last finalize
step, and it was never built to be retried: the one thing that kept a retry from filing twice was
that the roadmap write claimed its step once, up front, and filing rode on that same claim. A
data-plane step is retried by design, so filing needs its own way to recognise an issue it already
opened before it can move at all.

## Decision

**The roadmap write and issue filing are cut into separate steps, with the task existing before
its issue.** A new API step writes the roadmap and, beside it, records the issues it now wants —
one per new Task, with the repository, title and body. The Worker then files those issues with its
own credential, and a final API step links whatever it reports back to the Task it was filed for.

```
reviewer approves, gate finalizes (as today, through plan)
      |
      v
plan (API): as before, plus declares whether this gate writes a roadmap
      |
      v
GitHub step (Worker): re-check and merge, as before (skipped if nothing to merge)
      |
      v   only when the plan declares a roadmap write; otherwise complete as before
roadmap write step (API): claim once; write the roadmap with no GitHub call;
     store the gate's note and the issue requests on the approval; hand the
     claim time and the requests to the workflow
      |
      v   only when there are issues to file
issue step (Worker): list each repo's issues updated since the claim, looking
     for each task's hidden key; file only the tasks whose key is missing;
     return one result per task — issue number and url, or a failure
      |
      v
complete (API): link every filed result to its task, note any task with none,
     complete the gate exactly as today
```

**The write step keeps the existing one-time claim, now without GitHub in it.** It claims the
roadmap write, writes the roadmap, and stores its result — the note plus the issue requests — with
the approval. A repeated write step returns what is already stored instead of writing or filing
again.

**Each issue carries its task's identifier as a hidden comment, so a retry can find it instead of
re-filing it.** The issue step can be retried at any point, including after it opened an issue but
before anyone heard back. So before filing, it lists the repository's issues updated since the
roadmap write's claim time and collects every task key already present; it then opens an issue only
for a task whose key is still missing. The lookup uses GitHub's plain issue listing, not its search
index, because the index can lag behind a newly created issue by exactly the few seconds a retry
falls in.

**Filing stays best-effort, as it was before the split.** If the Worker cannot file some or all of
the requested issues — no Worker available within the step's budget, or GitHub kept failing — the
gate still completes, and its note names the Task(s) left without an issue. The roadmap is already
written by the time filing runs, so holding or refusing the gate at that point would not undo
anything.

**The split is declared by the plan step, not assumed.** An older control plane's plan says nothing
about writing a roadmap in two steps, so the workflow keeps the one-step completion it already had;
a workflow history already past the branch point when a deploy lands also keeps the commands it was
recorded with. Either protocol writes the roadmap once and links each filed issue to its Task; the
split one also adds a `GitHub issues: filed N of M.` line to the gate's note.

## Supersedes

- From [2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md), the rule that a
  gate with nothing to compare or merge never waits on a Worker. For a gate whose plan declares a
  roadmap write, it may now wait on the Worker's issue step even when it merges nothing, because
  filing the new Tasks' issues still needs the Worker.
- From [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md), the listing of issue
  filing among the control plane's remaining GitHub calls still to move. For a gate whose plan
  declares a roadmap write, that move is now done; the one-step path (see Consequences, below)
  still files from the control plane.

## Consequences

- **The roadmap write step hands the issue requests to the workflow, bounded to 256 KiB.** The
  requests travel through the workflow's history, which Temporal cannot grow past its default 2 MB
  blob limit — and Go's JSON escaping of arbitrary task text can grow it up to roughly six times
  over, so the stored requests are kept well under the blob limit rather than up against it. A
  request that does not fit the budget stays stored on the approval but is not handed to the
  Worker; the gate's note names its Task as not filed, the same as any other Task the Worker could
  not reach.
- **A future narrowed run token must keep issue-write scope.** Work tracked elsewhere plans to
  narrow a run's minted GitHub token to that run's own repositories. Whatever that change does, the
  narrowed token must still carry permission to create issues and must cover every repository an
  issue request can name — any repository of the run's project — or filing degrades silently to
  "not filed" with no error surfaced anywhere.
- **Rollout order is not required, only recommended.** The plan step's declaration makes every
  rollout order safe: an orchestrator newer than the API simply keeps the one-step complete until
  the API also understands the split. Rolling Workers no later than the API and orchestrator avoids
  a short window in which an approval's issue step reaches a Worker that does not yet have it; an
  approval that lands in that window still completes, with its note naming every requested Task's
  issue as not filed.
- **The one-step path is unchanged and still files from the API.** It continues to serve the
  synchronous gate protocol, any workflow history recorded before this split existed, and an
  orchestrator older than the API that never sends a roadmap-write plan.

## Rejected

- **File issues from the proposal, before the roadmap write, inside the merge step.** A Task write
  that fails after an issue was already opened leaves an issue with no Task, and there is no stable
  key to file against across re-approvals of the same proposal.
- **File issues after complete, then link them in a further API step.** The gate would be decided
  before its note could say what was filed, and this still needs an extra API step — it buys
  nothing over writing the roadmap and the issue requests together.
- **Recognise a retried issue through GitHub's search index, or through label-per-task, or through
  heartbeat progress alone.** Search lags a newly created issue by the seconds a retry falls in;
  heartbeat-only tracking still double-files across a crash between creating and heartbeating; one
  label per task fills every repository's label list with a per-task artefact that outlives the
  issue.
- **Keep the issues-to-file list only on the workflow, never on the approval record.** A repeated
  write step would have nothing to return, and the write step's whole point is that a repeat
  returns the same result instead of writing — or filing — again.
- **Store the issues to file as pending link rows with no issue number yet.** One table would
  hold an issue's whole life, but every reader of issue links would then have to skip pending
  rows, and two of its required columns would have to become optional.
- **Keep the gate finalizing until the Worker files every issue.** With the roadmap already
  written, holding the gate open protects nothing it hasn't already committed to, and blocks the
  run on a side effect whenever no live Worker is reachable or GitHub is down.
- **Require the API to deploy before the orchestrator, or treat a 404 from the new step as "API
  doesn't support this yet."** The order deployments actually roll in cannot be relied on; and a
  404 already means "approval not found," so reusing it as a capability signal would make a missing
  approval look like an old API.
