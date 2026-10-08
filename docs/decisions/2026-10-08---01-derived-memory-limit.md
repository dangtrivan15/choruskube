# A per-project memory request derives its container's memory limit

## Status

current

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

When a project sets a memory request and nothing sets a memory limit for that container, the
Kubernetes executor derives the limit:

```
limit = max(deployment limit, 1.4 × request)   rounded up to a whole Mi
```

1. **The deployment limit is a floor, not a fallback.** A request can raise a limit and never lower
   one, so a request sized from RSS keeps the deployment's room for file cache, and a request above
   the deployment limit launches instead of failing.
2. **1.4×, not 1.0–1.25×.** Because requests are RSS-based, the limit must hold owned memory plus
   hot cache; 1.4× sits between the limit-to-request ratios already running without thrash or OOM
   and under the common 2× production cap.
3. **A dind PodTemplate with no memory limit stays unlimited.** Deriving one there would *add* a
   ceiling the operator never set.
4. **An explicit per-execution `AgentResources.MemoryLimit` is applied verbatim**, and a request
   above it still fails the launch before any object is created.

The ratio is `memoryLimitHeadroomPercent` in `worker/executor/k8s/kubernetes.go`; the project forms
state the rule next to each request field.

## Rejected

- **`limit = 1.15 × request` with no floor.** Every limit for an RSS-sized request would shrink to
  just above owned memory, the configuration that thrashes.
- **`limit = request` (Guaranteed) above the floor.** Correct for peak-sized requests; for RSS-sized
  ones it leaves no room for file cache.
- **A separate per-project limit field.** Two numbers per container to keep consistent, for a value
  the request already determines.
- **Validating against the limit in the API server.** The API server cannot see a Worker's limits;
  a Worker may run in a different cluster with different defaults.
