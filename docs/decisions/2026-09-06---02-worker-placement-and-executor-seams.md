# The Worker's multi-Fleet seams ship as single-implementation interfaces, not dead code

## Status

current

## Context

The Worker extraction (see
[2026-09-05---01-worker-owns-tenant-agnostic-executor.md](2026-09-05---01-worker-owns-tenant-agnostic-executor.md))
and the credential work that followed
([2026-09-06---01-worker-scoped-internal-credential.md](2026-09-06---01-worker-scoped-internal-credential.md))
left core with four extension points that each have exactly one caller and exactly one
first-party implementation. A contributor scanning for unused abstractions could reasonably
read each of them as dead code and delete it — the interface, its single implementation, and
the `Optional<T>`/type-assertion fallback around it all disappear together with no compiler
error and no core behavior change. Recording why they exist is what stops that deletion.

## Decision

**Core never learns the word "Fleet" beyond routing to the one it has.** Four seams carry that
boundary, each replaced by supplying a bean or a type, never by editing core:

- **`WorkerRegistrar`** — which Fleet a Worker serves. Default: `SingleFleetWorkerRegistrar`,
  which always answers with the one namespace and queue from `temporal.namespace` /
  `temporal.task-queue`.
- **`RunPlacementResolver`** — which Fleet/namespace a run dispatches to. Absent, `RunPlacementService`
  falls back to the same configured `temporal.namespace` / `temporal.worker-task-queue`. It is
  the other half of the same arrangement as `WorkerRegistrar`: one decides which queue a Worker
  polls, the other which queue a run is dispatched to, and a multi-Fleet deployment replaces
  both together or neither.
- **`NodePlacementChecker`** — a dispatch-time gate, consulted once a node execution's row
  exists so a denial has somewhere to record its reason. Absent, every node is allowed, which is
  the single-always-present-Worker behavior core has always had.
- **`executor.CredentialConsumer`** (Go, `worker/executor/executor.go`) — lets an `Executor`
  receive the Worker's live, rotating api-server credential via a getter, wired in
  `worker/run.go`'s `wireExecutorCredential`. Neither shipped executor (Docker, Kubernetes)
  implements it: both resolve everything from static configuration and make no api-server calls
  of their own. It exists solely so a closed multi-tenant executor overlay — one that resolves
  per-org namespaces by calling the api-server itself — can adopt it without changing the Worker
  binary that constructs it.

## Alternatives considered

- **Delete the unused seams and let a fork reintroduce them.** Rejected: a fork carries no memory
  of how the deployment-facing pieces (`WorkerAuthFilter`, run-scoped workload routes,
  the credential-rotation loop) were shaped to leave exactly these four gaps. Reintroducing the
  seams later means re-deriving that shape from scratch, with no guarantee of arriving at one
  that still agrees with the routes and the credential model.
- **Default-method the seams to their fallback behavior** (e.g. `NodePlacementChecker` defaulting
  to `allowed`) so no `Optional`/type-assertion wrapper is needed at the call site. Rejected for
  the Java seams: `RunPlacementResolver`'s javadoc is explicit that the single-namespace fallback
  belongs in `RunPlacementService`, never in a default method — an implementation that exists
  knows about more than one place work can run, so a default method answering "the configured
  one" would silently route a run to a namespace nothing polls instead of failing to compile
  when a real multi-Fleet implementation forgets to override it.

## Consequences

- All four seams are exercised in core only through their single-tenant default — there is no
  test in this repository proving a *different* implementation composes correctly with the
  routes, the credential rotation loop, or the Worker binary. That proof lives with whoever ships
  the replacing implementation.
- `worker/run.go`'s `wireExecutorCredential` is a no-op for both shipped executors; it stays
  because the Worker binary must not change shape when an executor overlay adopts
  `CredentialConsumer` — only the executor construction call does.
- A future contributor removing any of these four for being "unused" is removing a boundary, not
  dead code — this entry is the reason to leave it, or to ask before taking it out.
