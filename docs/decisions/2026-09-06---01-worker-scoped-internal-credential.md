# A Worker authenticates on its own credential, scoped to the runs it may act on

## Status

current

## Context

Before the Worker extraction, the orchestrator made every workload call itself, authenticated
with `ORCHESTRATOR_SECRET` — a single shared secret with full access to `/internal/**`, held by
a process that always ran inside the deployment's own infrastructure. When workload creation
moved to a separate Worker process, the Worker's first workload client carried that same secret
forward: it was already a bearer token the api-server accepted, and nothing yet distinguished a
Worker from the orchestrator that used to make these calls.

That stopped being adequate once a Worker could run on infrastructure someone other than the
deployment owner controls — the whole point of the extraction. `ORCHESTRATOR_SECRET` grants
full `/internal/**` access; handing it to an external operator hands them every tenant's data,
not just the runs their Fleet serves.

## Decision

**`/worker/**` is a separate route surface, guarded by `WorkerAuthFilter`** — a sibling of
`InternalAuthFilter`, not an extension of it, because the two tiers carry different semantics
and `InternalAuthFilter` is change-gated. `WorkerAuthFilter` only proves a bearer token was
presented; it does not decide what the token is entitled to.

**`WorkerAuthorizer.requireMayActOn(credential, runId)` makes that decision, per request**,
binding a presented credential to the one run it may act on rather than granting blanket
access. Every workload route a Worker calls names its run in the path, so this is one check per
request, and a denial is a 403 from the domain layer rather than a 401 from the filter.

Core ships `SingleFleetWorkerAuthorizer`: any credential valid for the one configured Fleet may
act on any run, because a single-tenant deployment has no run a valid Worker is not entitled to.
A closed control plane replaces the `WorkerAuthorizer` bean with one that checks a live
Fleet-to-run pin instead — which is what makes revoking a Worker take effect on its next
request, rather than only once its long-lived secret is rotated everywhere.

Workers no longer present `ORCHESTRATOR_SECRET`.

## Alternatives considered

- **Keep the Worker on `ORCHESTRATOR_SECRET`.** Rejected: it is the decision this entry exists
  to undo — a shared, full-access secret is the wrong shape for a credential an external
  operator's infrastructure holds.
- **Fold Worker auth into `InternalAuthFilter`.** Rejected: that filter's orchestrator/agent
  tiers carry semantics (their own token formats, their own change-approval gate) that do not
  apply to Worker registration, and a third tier bolted onto a change-gated file makes every
  future Worker-auth change require the same approval as the orchestrator tier.

## Consequences

- A Worker's blast radius on compromise is the runs its Fleet serves, not the deployment.
- Revocation is a property of whatever `WorkerAuthorizer` bean is active, not of the route or
  the filter — core's single-Fleet default has nothing to revoke into, since one Fleet has one
  shared secret and one namespace to rotate it in.
- `WorkerRegistrar` and `WorkerAuthorizer` are deliberately shaped alike (see
  `WorkerRegistrar`'s own javadoc): one decides which Fleet a Worker serves, the other which
  runs follow from that, and a deployment replacing one typically replaces both.
