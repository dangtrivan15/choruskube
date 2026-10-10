# A per-project memory request derives its memory limit, capped unless exempt

**Status:** superseded by [2026-10-08---02](2026-10-08---02-memory-limit-always-derived.md)

## Context

A project can set the memory *request* of its agent container and of its dind sidecar
(`agent_memory_request`, `dind_memory_request`). Until now the *limit* always stayed the
deployment's (`K8S_AGENT_MEMORY_LIMIT` for the agent, the PodTemplate's dind limit for the sidecar),
and the Worker failed the launch when a request exceeded it. The API server accepts any valid
quantity because it never sees the Worker's limits, so a request above the limit saved cleanly and
then failed every node of that project at launch.

The two values do different jobs. The scheduler books the request; the kernel enforces the limit.
At the limit, the container's cgroup first drops file cache and only then OOM-kills, so a limit just
above the memory a workload owns makes it re-read its hot files from disk (I/O thrash) long before
it is killed. Requests here are sized from owned memory (RSS), not from the working set, which
includes active file cache. The gap between request and limit is where that cache lives.

Common practice for memory limits, all of which assume the request is near the real peak:

| practice | rule |
|---|---|
| limit = request | the most predictable QoS class (Guaranteed); recommended by the GKE cost-optimization guide |
| keep the ratio | the Vertical Pod Autoscaler scales a limit with its request, preserving the original limit-to-request ratio |
| peak + buffer | limit at observed peak plus 20–30%; production namespaces commonly cap limit/request near 2× (`LimitRange.maxLimitRequestRatio`) |

## Decision

The Kubernetes executor sizes a container that a project has set a memory request for in one of
two ways, chosen per run by the API server (`WorkloadMemoryCeilingPolicy`, carried to the Worker as
`memoryCeilingExempt` on the prepare response):

| run | rule |
|---|---|
| **exempt** | `limit = max(deployment limit, 1.4 × request)`, rounded up to a whole Mi |
| **capped** | `limit = deployment limit`; a request above it fails the launch before any object is created |

1. **The deployment limit is the ceiling for capped runs.** On a shared data plane a project must
   not reserve more than the operator sized a container for: the scheduler books requests, so one
   oversized request leaves every other tenant's pods Pending. The ceiling is the Worker's own
   `K8S_AGENT_MEMORY_LIMIT` and its PodTemplate's dind limit — no separate setting — because only
   the Worker can read them: a tenant may run its own data plane with its own PodTemplate, which the
   API server never sees. On that data plane the tenant's template is the ceiling, so it needs no
   exemption of its own.
2. **For exempt runs the deployment limit is a floor, not a fallback.** A request can raise a limit
   and never lower one, so a request sized from RSS keeps the deployment's room for file cache, and
   a request above the deployment limit launches instead of failing.
3. **1.4×, not 1.0–1.25×.** Because requests are RSS-based, the limit must hold owned memory plus
   hot cache; 1.4× sits between the limit-to-request ratios already running without thrash or OOM
   and under the common 2× production cap.
4. **Exempt by default, capped on doubt.** A single-tenant deployment's projects are all set by its
   operator, so the default policy (`ExemptMemoryCeilingPolicy`) exempts every run; a multi-tenant
   deployment supplies its own. A policy that throws caps the run, and a Worker talking to an API
   server that predates the field decodes it as `false` — a version skew caps, never uncaps.
5. **A dind PodTemplate with no memory limit stays unlimited** for both kinds of run: deriving one
   would *add* a ceiling the operator never set.
6. **An explicit per-execution `AgentResources.MemoryLimit` is applied verbatim**, and a request
   above it still fails the launch.

The ratio is `memoryLimitHeadroomPercent` in `worker/executor/k8s/kubernetes.go`; the project forms
state the rule next to each request field.

## Rejected

- **`limit = 1.15 × request` with no floor.** Every limit for an RSS-sized request would shrink to
  just above owned memory, the configuration that thrashes.
- **`limit = request` (Guaranteed) above the floor.** Correct for peak-sized requests; for RSS-sized
  ones it leaves no room for file cache.
- **A separate per-project limit field.** Two numbers per container to keep consistent, for a value
  the request already determines.
- **Enforcing the ceiling in the API server when a project is saved.** It would fail earlier, in
  the form, but the API server cannot see a Worker's limits: it would need its own copy of the
  value, and could never know a tenant-run data plane's PodTemplate.
- **A separate ceiling setting on the Worker.** One more value to keep in step with the limits it
  bounds; the deployment limit already says how much a container was sized for.
- **No ceiling.** Any member able to edit a project could reserve a whole node of a shared data
  plane.
