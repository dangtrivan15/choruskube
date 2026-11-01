# Node-execution routes check run membership at dispatch, not per handler

**Status:** current

## Context

Every agent-facing route under `/internal/runs/{runId}/node-executions/{nodeExecId}/**` and
`/worker/runs/{runId}/node-executions/{nodeExecId}/**` names two things: the run, and an execution
within it. `InternalAuthFilter` and `WorkerAuthFilter` confirm only that the caller's credential
belongs to the named execution — neither compares the path's `runId` to that execution's actual
run. A handful of handlers added their own cross-check over time; most did not, so a caller could
keep its own execution id, pair it with a different run's id, and have a handler trust the run id
instead.

Auditing every route also turned up a defect in how the two filters decide whether to run at all:
both gate on `request.getRequestURI()`, the raw, still-percent-escaped path, while Spring MVC
dispatches on the path after it decodes those escapes. A path whose first segment is spelled with
an escape (`/%69nternal/...`) therefore never reaches either filter's authentication check, yet
still reaches its handler — with no credential presented at all. Per-route membership checks mean
nothing while that holds, because the caller can simply spell a run and an execution that really
do belong together.

## Decision

**One `HandlerInterceptor`, `RunScopeInterceptor`, runs after both auth filters and before any
handler binds or validates its request**, over every route matching `/internal/**` or
`/worker/runs/**`. It applies two rules:

1. **Canonical path.** A raw request path containing a percent-escape is refused with 400 on any
   `/internal/**` route, and on any route of either family shaped like rule 2 below — so an
   execution-scoped `/worker/**` route gets this too, while a run-level `/worker/**` route does
   not, because its own handler already fails closed on a missing credential.
2. **Membership.** A route shaped `/{internal|worker}/runs/{runId}/node-executions/{nodeExecId}/**`
   is refused with 404 — the same body callers already get for an unknown execution — when that
   execution does not belong to that run. A route whose first five segments match this shape but
   whose variables are named anything other than `runId`/`nodeExecId` fails closed with 500, so a
   misnamed route cannot silently skip membership checking.

The interceptor recognises a route by its *shape*, derived from `HandlerMapping`'s matched pattern
string at request time, not from a hand-maintained list — so it covers a route nobody has written
yet. The variable names are therefore a contract every future execution-scoped route must follow.

**The escape rule is what makes rule 2 trustworthy.** The two auth filters and the interceptor
must agree on which path they are each looking at. They already do for a canonical path; the only
divergence is escape handling, and the request firewall already rejects every other
non-canonical spelling (path parameters, doubled slashes, dot segments, encoded slashes). Refusing
an escape at the interceptor closes the unauthenticated-bypass defect above on every `/internal`
route — including ones that name no execution — without editing either filter.

**A shared route audit proves coverage**, as a public test-fixture class (`RunScopeRouteAudit`)
one application's test runs over its own `RequestMappingHandlerMapping` and `MockMvc`. It walks
every mapped `/internal/**` and `/worker/runs/**` route and checks, in one failure report covering
every violation:

- every execution-scoped route carries the interceptor and refuses a real execution from one run
  presented under another, with the exact refusal body;
- every `/internal` route, and every execution-scoped `/worker` route, refuses its escaped
  spelling with the exact 400 body;
- every other `/internal` route still answers an execution's own credential with 401, and its
  pattern contains no catch-all or regex-constrained variable, and no five-segment window —
  starting at any segment, including the first — shaped like `internal/runs/{x}/node-executions/{y}`
  that the filter's unanchored match could spell from an attacker-controlled variable value;
  run-level `/worker` routes are exempt from this rule, since the filter that gates them shares
  the escape defect but not the membership one, and every current handler on them already fails
  closed on a missing credential;
- the walk is not vacuous: at least one execution-scoped route of each family, and one run-level
  `/internal` route, must exist, and the two ids under test must have been supplied.

Because the fixture takes its route table as an argument, an application composing these routes
with routes of its own runs the identical audit over its composed table, so a route the composing
application adds later is covered without anyone remembering to list it.

## Consequences

- **A cross-run presign, and presign for an unknown execution, now answer 404 (plain text)** where
  they previously answered 403 (JSON) — the interceptor's uniform refusal, not the handler's own
  check, which stays only as defence in depth for non-HTTP callers of the same service.
- **An unknown execution now answers 404 on input-artifacts and the placement check** (previously
  200) **and on a log write** (previously 500), because the interceptor refuses before either
  handler runs.
- **On `/worker/**` routes, membership is answered before the Worker credential is authorized.**
  Those controllers authorize their credential inside the handler, so on an execution-scoped route
  the interceptor's 404 now precedes that check. A caller presenting any bearer value learns
  whether a given run/execution pair belongs together before learning whether its credential is
  valid for anything. The two ids involved are random version-4 UUIDs a caller must already hold,
  so this reveals a pairing between ids already known, not which ids exist.
- **Existing per-route membership checks are unchanged** and remain in place as defence in depth
  for callers that reach the same service methods outside HTTP.
- **No route's URL changed.** One controller's path variable was renamed from `execId` to
  `nodeExecId` to satisfy the interceptor's naming contract; the URL template is identical.

## Rejected

- **A per-route call to a shared membership-check service**, the status quo before this change.
  Explicit at each call site, but it runs after a handler has already started binding and
  validating its request, and every new route has to remember to add it — which is exactly how
  most of the routes above came to lack one.
- **Putting the check inside the existing auth filters.** A filter decides whether to run at all
  from the request's raw path, so it never observes a request whose escape handling is the actual
  problem; and by the time a filter runs, the router has not yet bound the path variables the
  membership check needs. A filter-based check would therefore miss both of the defects this
  change closes. The filters' own raw-path gating, unanchored match and missing run comparison are
  separate, longer-standing defects this interceptor compensates for without editing either file.
- **Scoping the interceptor to agent credentials only, leaving the orchestrator's calls
  unenforced.** Telling tiers apart from inside the interceptor would mean teaching it about
  filter internals it has no other reason to know, and no orchestrator call path ever sends a
  mismatched run/execution pair, so the restriction would buy nothing.
- **Answering a mismatch with 403 instead of 404.** 403 would confirm that the named execution
  exists somewhere, just not in the named run — a distinction no existing membership check in this
  codebase draws, and one worth keeping uniform across all of them.
