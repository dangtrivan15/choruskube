# Every memory limit is 1.4× its request; the default request is the ceiling

**Status:** current — supersedes [2026-10-08---01-derived-memory-limit.md](2026-10-08---01-derived-memory-limit.md)

## Context

[2026-10-08---01](2026-10-08---01-derived-memory-limit.md) derived a project's memory limit as
`max(deployment limit, 1.4 × request)` for exempt runs and kept the deployment limit for capped
ones. One configured number per container (`K8S_AGENT_MEMORY_LIMIT`, the PodTemplate's dind limit)
did three jobs: the limit of a project with no request, the ceiling of a capped run, and the floor
under an exempt one. Lowering it to free memory also lowered every capped project's ceiling and
every exempt project's floor, and reading a pod's limit meant knowing which of the three applied.

## Decision

| container | request | limit |
|---|---|---|
| agent | the project's `agent_memory_request`, else `K8S_AGENT_MEMORY_REQUEST` | 1.4 × request, rounded up to a whole Mi |
| dind | the project's `dind_memory_request`, else the PodTemplate's dind memory request | 1.4 × request, rounded up to a whole Mi |

1. **No memory limit is configured anywhere.** `K8S_AGENT_MEMORY_LIMIT` is removed and the Worker
   refuses to start while it is set; a PodTemplate whose dind container declares a memory limit
   fails validation. Either would otherwise be replaced without a trace, and the operator would
   believe in a limit no pod runs with.
2. **The default request is the ceiling for capped runs.** A capped project may set a request below
   the deployment default, not above it; above fails the launch before any object is created. With
   no default request to compare against, a capped project's request is refused rather than left
   unbounded. Exempt runs may set any request. Which runs are exempt is unchanged
   (`WorkloadMemoryCeilingPolicy`: exempt by default, capped on doubt).
3. **1.4× with no floor.** Requests are sized from a project's measured owned memory (RSS) plus a
   margin, so 1.4× leaves room for hot file cache in proportion to the project's size, for the
   reasons in 2026-10-08---01's point 3. A project whose cache needs more raises its request, which
   raises its limit. The floor only covered projects nobody had measured.
4. **The default agent request is 2Gi** (was 1Gi under a 3Gi limit), so an unconfigured
   deployment's agent limit is 2868Mi rather than 1434Mi.
5. **`AgentResources.MemoryLimit` is removed** from the executor contract; nothing set it.
6. **A dind sidecar with no memory request anywhere stays unlimited**, as the operator wrote it.

The ratio is `memoryLimitHeadroomPercent` in `worker/executor/k8s/kubernetes.go`; the project forms
state the rule next to each request field.

## Trade-offs

- A project on the default request gets 1.4× the default, not a larger deployment limit. A heavier
  project nobody has measured now thrashes or is OOM-killed instead of borrowing that headroom; its
  fix is a request of its own.
- Upgrading is breaking for operators: unset `K8S_AGENT_MEMORY_LIMIT` and remove the dind memory
  limit from the PodTemplate before the new Worker starts.

## Rejected

- **Keep the floor.** One value doing three jobs, as above.
- **Honor a declared limit when present, derive otherwise.** Two modes to reason about per
  container, and a declared limit just above the request is the configuration that thrashes.
- **Ignore a declared limit with a log warning.** Pods would change limit silently for anyone who
  misses the line.
- **A separate ceiling setting.** As in 2026-10-08---01: the default request already says how much a
  container was sized for.
