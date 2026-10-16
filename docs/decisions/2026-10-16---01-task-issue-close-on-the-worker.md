# A Task's GitHub issue is closed by a short workflow on the Worker

**Status:** current

## Context

When a Task reaches `done`, the GitHub issue filed for it closes. The API server made that call
itself, inside the completion's own database transaction, so a completion that then rolled back
could still have closed the issue. [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md)
lists issue closing among the control plane's GitHub calls still to move, and rejects keeping one
for work outside a run; [2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md)
left it out of scope. It cannot ride a run workflow's step as gate finalize does: a Task completes
outside any run, and the Worker accepts no inbound connections. Waiting for the org's next run was
rejected too, because an org with no further runs would never get its issue closed.

## Decision

- **The close starts after the completion commits,** as `CloseTaskIssueWorkflow`
  (`choruskube-task-issue-close-<taskId>`) in the namespace of the Task's latest run, on the
  orchestrator's own task queue.
- **The GitHub step runs on that run's Worker task queue.** The Worker fetches a token from its
  own credential provider, keyed by the run id, and closes the issue. The token never enters the
  workflow's input.
- **The API records the result** through an orchestrator-run activity calling
  `POST /internal/tasks/{taskId}/github-issue/closed`; every write stays on the API.
- **No schedule-to-start bound.** A Fleet with no live Worker leaves the close waiting, not failed.
  Retries are bounded once a Worker actually runs the step.
- **A GitHub refusal ends the job with nothing recorded,** and the reason is logged.
- **A failed hand-off is logged and not retried.** If Temporal is unreachable at commit time, the
  issue stays open and the completion still stands.
- **Placement asked about an already-ended run answers with its recorded placement,** never
  choosing again, so on a deployment that places runs on more than one Fleet the close follows
  the run's own Fleet.

## Supersedes

- From [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md), its listing of issue
  closing among the control plane's remaining GitHub calls. That entry's other rules — the two
  credential modes, and that `local` is for a Fleet serving one tenant — are unaffected.

## Consequences

- **Issue closing leaves the list of GitHub calls still made from the control plane.**
- **Deploy order is api-server, then Worker, then orchestrator,** and each mis-ordering is bounded:
  - api-server ahead of the orchestrator: started close workflows wait as unknown-type workflow
    tasks and run once the new orchestrator registers the type;
  - orchestrator ahead of the Worker: the step fails as an unknown activity and uses up its
    bounded retries, which only matters if the Worker lags by more than about two hours;
  - orchestrator ahead of the api-server: no close workflows exist to schedule.
- **A Fleet that never comes back leaves its close workflow open** until an operator terminates it
  and closes the issue by hand.
- **A Worker running an older image exhausts its retries** on the new step as an unknown activity,
  and the issue stays open.
- **A future token narrowed to a run's own repositories must still reach the issue's repository.**
  The issue's repository is one of the Task's project repositories, so this already holds.

## Rejected

- **Resolve the org's current default Fleet and mint a new Task-scoped token route for it.** It adds
  an authority grant to design and secure, where the run's own placement and token route are
  already audited.
- **Have the API resolve the GitHub token upfront and pass it in the workflow input.** A live token
  would sit at rest in Temporal history, and could expire while the job waits for a Worker.
- **Have the Worker report the result through a new Worker-facing write route, or leave the
  linkage row unupdated.** The first adds a second write path to secure; the second leaves the row
  stating something false.
- **A short pickup deadline, or a long cap on the whole workflow.** Either silently drops the close
  for a Fleet that is offline longer than the bound, and nobody is waiting on it.
- **Deliver the close through an outbox row written in the completion's transaction.** It needs a
  schema change and a sweeper only to survive Temporal being down at the moment of commit; the
  durable queue already absorbs the commoner failures, a missing Worker and a GitHub outage.
