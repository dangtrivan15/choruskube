# The Worker owns a tenant-agnostic workload executor

## Status

current

## Context

Workload creation — launching the agent's Docker container or Kubernetes Job for a
node execution — originally lived in the api-server, which owned a `WorkloadExecutor`
and created the workload in-process. The orchestrator delegated to it over HTTP.

The Worker extraction makes a **Worker** the process that runs node activities, so a
Worker can eventually run on infrastructure an operator controls rather than always
inside the deployment's own cluster. Two questions had to be settled for the code to
take its current shape, and a later reader would otherwise guess at both:

1. Which component creates the workload?
2. Where does the Kubernetes executor live, given that the core is single-tenant while a
   multi-tenant deployment adds per-tenant namespaces, RBAC, and credentials around it?

## Decision

**The Worker owns the workload executor.** The orchestrator dispatches each ready node
as a Temporal activity; a Worker subscribed to the node's task queue picks it up and
creates the workload itself. The api-server leaves the execution hot path: it resolves
per-run credentials for the Worker (the workload `prepare` call) and records the
Worker-reported result (the workload `complete` call), and remains the source of truth
for state.

**The executor is tenant-agnostic, and the generic executor is public.** A single
`Executor` interface in the public `worker` module has two implementations — a Docker
executor and a Kubernetes executor. Each launches into a namespace, service account, and
credentials it is *given* in its `ExecutionParams`; neither resolves an organization, a
namespace, or a credential itself. Any multi-tenant resolution — which namespace, which
RBAC and NetworkPolicy, which registry, which per-tenant token — belongs to whatever
provisions tenants in a multi-tenant deployment. In the single-tenant core there is one
tenant, so that boundary is invisible; but the Kubernetes executor carries no tenant
logic, so it ships in the public module alongside the Docker one.

## Alternatives considered

- **Keep the executor in the api-server.** Rejected: it couples workload creation to the
  state database, and a self-hosted Worker would have to call back into the deployment's
  api-server to create pods — which creates them in the deployment's cluster, the opposite
  of self-hosting.
- **Ship only the Docker executor in the open module, keeping Kubernetes execution out of
  it.** Rejected: the Kubernetes executor is a generic Job launcher and carries none of the
  multi-tenant value (that lives in tenant provisioning, which is factored out entirely).
  A self-hoster running on Kubernetes needs it, and the core already advertises Kubernetes
  execution.

## Consequences

- One Worker binary supports both executors, selected at startup (`EXECUTOR_TYPE`). A
  self-hoster on a laptop uses Docker with no configuration; a cluster deployment selects
  Kubernetes.
- The tenant boundary is enforced by the module boundary: tenant-coupling cannot creep
  into a public executor without showing up in a public diff.
- The api-server is no longer on the execution hot path — its role narrows to credential
  resolution and state, and a workload failure is a Worker concern.
