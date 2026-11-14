# Auth filters authenticate the decoded, routed path and compare the run

**Status:** current

## Context

`InternalAuthFilter` and `WorkerAuthFilter` decided whether to act on a request from its raw,
still-escaped URI, while Spring MVC routes on the path after decoding percent-escapes. A request
whose prefix was spelled with a percent-escape (`/%69nternal/...`, `/%77orker/...`) therefore never
reached either filter's authentication logic at all, even though the router dispatched it to a
normal handler exactly as if it had been spelled plainly. `RunScopeInterceptor`
([2026-10-31---03](2026-10-31---03-node-execution-routes-check-run-membership-at-dispatch.md))
closed that gap as a second, independent layer, refusing the escaped spelling itself — but the
filters stayed the only layer a caller's credential had to pass, and the internal filter's agent
tier found an execution id anywhere in the path rather than at a fixed position, and never compared
the execution's own run to the run named in the path. An agent's own secret, presented against its
own execution id but a different run's id in the path, authenticated at the filter and reached the
guard only to be refused there with the same 404 an unknown execution gets.

## Decision

**Both filters decide, and the internal filter authenticates, from the decoded
path-within-application segments the router itself dispatches on** — not the raw URI. One helper,
shared by both filters, parses `request.getRequestURI()` with
`RequestPath.parse(...).pathWithinApplication()` and reads each segment's `valueToMatch()`: the
decoded, path-parameter-free value `PathPattern` routing matches on. A percent-escaped spelling of
either filter's prefix is now authenticated, or refused, exactly like the plain spelling — it is
`RunScopeInterceptor`, reading the raw URI, that still refuses the escape itself afterward.

**The internal filter's agent tier is anchored at a fixed window, and compares the run.** It matches
only when the decoded segments at positions 0–4 are exactly `internal`, `runs`, a run id,
`node-executions`, an execution id — optionally followed by more segments — not when that shape
appears anywhere else in the path. Both ids must parse as UUIDs, the execution must exist, its stored
`job_secret_hash` must equal the presented token's hash, **and its own run must equal the run id at
this window.** Anything else falls through to the filter's existing 401. The orchestrator tier is
unchanged: its shared secret still authenticates with full access, never compared against the run.

**The Worker filter gets only the decoded-path fix.** It has no execution segment in its path to
compare against a run, so `shouldNotFilter` reads the same decoded first segment and nothing else
about it changes — it still only proves a bearer was presented.

**`RunScopeInterceptor` keeps its escape refusal and its membership check**, unchanged in behaviour,
as the independent layer under the filters' next edit. The route audit fixture now proves both
layers on every execution-scoped route: an agent's own execution under another run gets the
filter's 401; the same pair with the orchestrator credential still gets the guard's 404; an escaped
spelling with a valid credential gets the guard's 400; an escaped spelling with no credential gets
the filter's 401. The fixture takes the orchestrator credential as a new input so it can make the
second and third of these probes itself.

This supersedes part of
[2026-10-31---03](2026-10-31---03-node-execution-routes-check-run-membership-at-dispatch.md): its
rejection of a check inside the filters, written when the filters were raw-path, unanchored, and
run-blind, and the 404 that entry's design gave an agent's own credential under another run. The
guard that entry added stays, for the reasons recorded there and here.

## Consequences

- **An agent's own execution under another run now answers 401 from the filter**, not the guard's
  404. Every test and the live e2e spec that asserted the 404 for this pair move to 401.
- **An execution id anywhere in the path other than the fixed window authenticates nothing.** A
  route shape that embeds the agent window away from the start, or twice, no longer offers a second
  way in — though the route audit's structural check keeps flagging that shape, since a route of it
  would still be confusable with the real window if the filter's own anchoring were ever loosened.
- **A percent-escaped prefix is authenticated like its plain spelling.** A caller with no credential
  now gets the filter's 401 on an escaped path that previously fell through to the guard's 400
  unauthenticated; a caller with a valid credential still reaches the guard's 400, now provably
  because the filter authenticated it first.
- **No caller-visible change for the orchestrator tier or for honest callers.** The orchestrator's
  shared secret and an agent's own, correctly-scoped request behave exactly as before.

## Rejected

- **Leaving the filter to defer to the guard's 404 for an agent's own execution under another
  run.** No caller-visible change, but the filter would still never compare the run, leaving the
  guard as the only layer that does — exactly the single point of failure this change removes.
- **Dropping the guard's escape refusal now that the filters read the decoded path.** Would leave
  one owner for path handling, but the filters are change-gated files; the guard's refusal is the
  net under their next edit, at the cost of enforcing the same rule in two places.
