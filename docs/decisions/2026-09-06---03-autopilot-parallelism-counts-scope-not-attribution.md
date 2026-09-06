# Autopilot occupancy is counted by scope, and that scope is deployment-defined

## Status

current

## Context

`max_parallel` is the Autopilot's ceiling on concurrent agent pods — a resource and cost
throttle. It was enforced by counting runs with a matching `autopilot_id`
(`WorkflowRunRepository#countByAutopilotIdAndStatusIn`), inline in `AutopilotService`. But
`autopilot_id` is null for any run a person started by hand (see `RunResponse#autopilotId`), so
that count silently omitted every manually started pod. In a multi-tenant deployment the failure
was concrete: with `max_parallel = 1` and one member's manual run in flight, the org's Autopilot
still read a free slot and launched a second pod, oversubscribing — and the status panel reported
the same phantom slot back, so the number never matched what was running.

The pull toward the old behaviour is that the Autopilot's *failure breaker* genuinely must stay
attribution-scoped: three of the Autopilot's own runs failing should disengage it, but a person
cancelling their own run is not the Autopilot failing. Occupancy and outcome look like the same
"count this Autopilot's runs" query, but they are two different questions, and collapsing them is
what produced the miscount.

## Decision

**Occupancy is counted by the Autopilot's scope, and what "in scope" means is left to the
deployment** — because the two deployments have genuinely different scopes to count, and only one
of them has a robust key for the wider count.

- A new seam, **`AutopilotSlotCounter`**, answers "how many live pods occupy a slot in this
  Autopilot's scope", keyed on the `autopilotId` the caller already holds (never request-scoped
  tenant state — the tick runs on a timer thread). This mirrors `AutopilotCandidateSource`.
- Default **`SingleTenantAutopilotSlotCounter`** (`auth.enabled=false`/`matchIfMissing=true`)
  counts the Autopilot's own attributed runs — `countByAutopilotIdAndStatusIn`, the pre-existing
  behaviour, now behind the seam. It does **not** count a person's manual run. That undercount is
  a narrow, same-operator concern single-tenant, and there is no per-run ownership to scope a
  wider count by; counting *every* run row instead would (a) rarely differ in production and
  (b) have no deterministic expression under the shared, never-reset integration database, where
  it folds in whatever other non-transactional tests have committed.
- A multi-tenant **overlay** (`auth.enabled=true`) counts every live run the Autopilot's
  organization owns, via its run-ownership records — the Autopilot's own runs and a member's manual
  ones alike, but never another org's. This is the fix: multi-tenant has the clean ownership scope
  single-tenant lacks. It replaces the default exactly as the overlay already replaces the epic
  candidate source (`AllEpicsCandidateSource`).
- `AutopilotService` routes all three occupancy reads — the planning budget, the per-start
  re-check, and the status panel's `inFlight`/`slots` — through the seam, so the panel can never
  advertise a slot the tick will refuse to fill.
- The **failure breaker deliberately does not** go through the seam. It keeps its
  `autopilot_id`-scoped settle query, because it counts run *outcomes* and only the Autopilot's
  own. The status panel's "awaiting you" / "needs attention" lists and epic-affinity ordering
  likewise still read the Autopilot's own runs — those are about the work it manages, not the
  capacity it must not exceed.

## Consequences

- Multi-tenant, `max_parallel` now means "at most N concurrent agent pods this org owns", which is
  what a member setting it expects: a person starting work by hand reduces what the Autopilot will
  start, rather than stacking on top of it. Single-tenant behaviour is unchanged.
- Occupancy (scoped) and outcome (Autopilot-only) are now visibly different queries. A future
  change to one must not be "simplified" back into the other.
- The seam is one more single-implementation interface in core until an overlay supplies the
  multi-tenant bean; deleting it as unused would reintroduce the miscount for every downstream
  tenant with no core test failing.
- The single-tenant undercount is left open on purpose, not overlooked. If it ever needs closing,
  it needs a run scope core can count robustly — not a raw installation-wide `count`.
