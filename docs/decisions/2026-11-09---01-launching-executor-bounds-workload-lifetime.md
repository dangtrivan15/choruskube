# The executor that launches a workload bounds its lifetime

**Status:** current

## Context

The orchestrator's reconciler used to reap orphaned workloads: it polled the api-server's
run status and the workload-executor routes to find executions whose run had gone terminal, and
terminated them. Those routes are gone, so the data plane needs a replacement for runs whose
workflow never gets to call `DeleteAgentJob` — a crashed Worker, a rolled-back orchestrator
replaying no further, a node that fails after its agent already reported, a quota park, a
workflow that completes but whose removal step itself fails.

Whatever replaces the reconciler has to answer who removes a workload once its node no longer
needs it, without reintroducing the routes that just left.

## Decision

**The executor that launched a workload bounds its own lifetime, from a deadline and a
retention stamped onto the workload at launch — no workflow or API-server involvement.**

- `executor.Bounds{ActivityTimeout, HeartbeatTimeout}` travels in `ExecutionParams`, set by
  `executeLocally` from the launching activity's own `StartToCloseTimeout`/`HeartbeatTimeout`
  (`worker/activity/activities.go`). `Bounds.Deadline()` adds `LogGrace` (2 minutes, covering the
  workflow's own three 30s log-fetch attempts) so the workflow's failure path still gets to read
  a timed-out node's logs before the workload is reclaimed. `Bounds.Retention(setting)` floors
  the Worker's configured `WORKLOAD_RETENTION` (`executor.ParseRetention`, default 1h) at
  `HeartbeatTimeout + LogGrace` — an agent that dies without reporting is only noticed when its
  heartbeat timeout fires, and the failure path reads its logs then.
- **Kubernetes:** the Job carries `ActiveDeadlineSeconds` and `TTLSecondsAfterFinished` computed
  from `Bounds`. Kubernetes itself stops the Job at its deadline and deletes it, and everything
  it owns, at its TTL — no Worker-side polling.
- **Docker:** every object a launch creates (the agent container, its DinD sidecar, both
  volumes) carries the deadline and retention as labels, because Docker has no native
  equivalent of `ActiveDeadlineSeconds`/TTL. A janitor goroutine owned by the `DockerExecutor`
  (`New` starts it, `Close` stops it) sweeps on an interval, reading those labels straight off
  the daemon rather than any in-memory state, and reclaims a group once its state and bounds
  call for it.

## Alternatives considered

| Option | Gives us | Costs us |
|---|---|---|
| ✅ **The launching executor, from a deadline and a retention stamped at launch** | covers every outcome including a workflow that is gone, with no workflow change | a janitor loop in the Docker executor and labels on every object it creates; a new Worker setting every entrypoint must read; finished workloads, and the credentials in them, linger up to the retention |
| The workflow's removal step, on every outcome | immediate removal by one owner | a new version marker and its own deletion Task; a terminated workflow removes nothing |
| A Worker sweep that lists workloads and asks the API which executions ended | catches any orphan by its state | a new API route, and a list across tenant namespaces the executor seams ([2026-09-05---01](2026-09-05---01-worker-owns-tenant-agnostic-executor.md)) forbid |
| Docker auto-removal plus the existing Job TTL | least code | Docker loses failure logs and still leaks sidecars and volumes; an abandoned running workload stays unbounded |

**Decided by:** only the executor still sees a workload after its workflow is gone, and it needs
nothing but the workload itself — no run status, no API call — to act.

## How this fits the executor-seam decisions

This is consistent with
[2026-09-05---01](2026-09-05---01-worker-owns-tenant-agnostic-executor.md) and
[2026-09-06---02](2026-09-06---02-worker-placement-and-executor-seams.md): removal goes by the
workload's own identity (Kubernetes' owner references, Docker's deterministic per-execution
names) or by the executor's own label query against its own daemon, never by a cluster-wide or
cross-tenant list. The Docker janitor only ever acts on workloads whose staging directory lies
under its own configured staging base, so a daemon shared by more than one Worker never has one
Worker's janitor touch another's files.

## Consequences

- A finished workload, and any credentials embedded in it (a job secret, a Claude token), lingers
  for up to the configured retention. `WORKLOAD_RETENTION` must never be set below the deployment's
  own node heartbeat timeout plus `LogGrace` — a smaller value is silently floored, never shortened
  below what the failure path needs to still read a crashed agent's logs.
- `Execute` on both executors now refuses to launch at all when `Bounds.ActivityTimeout` is zero or
  negative: an unbounded workload would never reach its own deadline or TTL, and nothing but a
  human would ever reclaim it.
- The orchestrator's reconciler, its workload-list/terminate API calls, and the API's internal
  run-status route that only the reconciler called are deleted outright, along with the dead
  `TEMPORAL_TASK_QUEUE` orchestrator setting and the unused `Activities.config` field. The
  orchestrator's Dockerfile now declares its real health port (8080) instead of the dead 9090 the
  reconciler's callback server used to answer on.
- A Docker host whose Worker process never restarts keeps its abandoned workloads until something
  restarts a Worker there (accepted, revisit only if such hosts stop being restarted) — and a launch
  killed between creating its staging directory and labelling the first object leaves an
  unlabelled directory neither Cleanup nor the janitor can find (accepted, revisit if such
  directories turn up in practice).
