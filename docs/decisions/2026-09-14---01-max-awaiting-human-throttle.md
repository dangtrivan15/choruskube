# `max_awaiting_human` is a second ceiling that reuses the slot-counter seam and only ever throttles

## Status

current

## Context

`max_parallel` throttles concurrent agent pods, but nothing capped how many runs a single
Autopilot could park on a human at once (`awaiting_human` + `paused`). Because a parked run
frees its `max_parallel` slot by design
([2026-09-06---03-autopilot-parallelism-counts-scope-not-attribution.md](2026-09-06---03-autopilot-parallelism-counts-scope-not-attribution.md)),
an Autopilot with a generous `max_parallel` and a large ready frontier could park an unbounded
number of runs on a human faster than any one person can review them — the ceiling meant to
control cost had no counterpart controlling review backlog.

Two things were already in place that a naive implementation could easily have duplicated
instead of reused:

- `AutopilotSlotCounter.occupiedSlots(autopilotId, occupyingStatuses)` already takes the status
  set as a parameter specifically so a caller defines "what counts" without the seam itself
  encoding it — `max_parallel`'s planning phase, its per-start re-check, and the status panel's
  `inFlight` all already call it with different status sets.
- `AutopilotStatusResponse.awaitingYou` and `AutopilotService.classify`'s `Bucket.AWAITING_YOU`
  already name the exact status pair (`awaiting_human`, `paused`) a new ceiling would need to
  count.

## Decision

**`max_awaiting_human` is a second, independent ceiling that reuses both existing seams rather
than adding new ones, and it only ever throttles — it never disengages.**

- **Reuse the parameterized slot-counter seam.** `AutopilotService.AWAITING_HUMAN` is derived
  from `classify`'s `Bucket.AWAITING_YOU` (`statusesWhere(c -> c.bucket() == Bucket.AWAITING_YOU)`),
  the same way `OCCUPIES_A_SLOT` is derived from `occupiesSlot()`. The start loop's per-item check
  is `slotCounter.occupiedSlots(autopilotId, AWAITING_HUMAN) >= maxAwaitingHuman`, issued right
  after the existing `max_parallel` check, in the same per-item, re-read style. No second
  `AutopilotSlotCounter`-shaped interface was added — the seam already answered the exact question
  this ceiling needs asked with a different status set.
- **The status panel's `awaitingHuman` field goes through the same seam call as `inFlight`, not
  through `awaitingYou.size()`.** `awaitingYou` is attribution-scoped (this Autopilot's own runs
  only); `awaitingHuman` must be occupancy-scoped, for the identical reason
  [2026-09-06---03](2026-09-06---03-autopilot-parallelism-counts-scope-not-attribution.md) gives
  for `inFlight` vs. a naive `autopilot_id`-scoped count — a multi-tenant overlay's org-scoped
  counter must see a member's manually parked run too, or the ceiling undercounts exactly the way
  the pre-2026-09-06 `inFlight` did.
- **A throttle, never a disengage.** Reaching `max_awaiting_human` only breaks the start loop for
  the rest of that pass. It does not increment `consecutive_failures` and does not call any
  `disengage*` statement on `AutopilotRepository` — a backlog of runs waiting on a human review is
  expected, normal operation, not a platform fault. Conflating the two would let ordinary review
  latency spend the three-strikes failure budget the breaker exists to protect, and would report a
  misleading `disengagedReason` naming "failures" that never happened.
- **`0` is the unlimited sentinel**, matching `max_parallel`'s existing `NOT NULL DEFAULT`
  convention (`V27__autopilot_max_awaiting_human.sql`) so every existing row and the id-only
  `insertDefaults` seed insert stay valid without a migration backfill.
- The two ceilings are independent PATCH fields (`AutopilotUpdateRequest.maxParallel`,
  `.maxAwaitingHuman`) — either being null leaves that ceiling unchanged, so a client can move one
  without having to know or repeat the other's current value.

## Consequences

- A future ceiling shaped like this one (a cap on occupancy of some `classify`-derived bucket)
  should reuse `AutopilotSlotCounter` and a `Bucket`-derived status set the same way, rather than
  adding a bespoke counting method — the seam's whole value is that "what counts as occupying this
  ceiling" stays a parameter, not a new interface per ceiling.
- `awaitingHuman` and `awaitingYou` will continue to read differently from each other
  multi-tenant, by design — a future change must not "simplify" them back into one field, for the
  same reason `inFlight` and `awaitingYou.size()` were never allowed to converge either.
- The failure breaker and the safety valve remain reachable only through run *outcomes* and
  external faults respectively; `max_awaiting_human` adds no new path to either, so a review of
  either mechanism's blast radius does not need to consider this ceiling.
