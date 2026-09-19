# The manual tick is org-scoped and runs regardless of engagement

## Status

current

## Context

`AutopilotService.tick()` is the scheduler's pass. It exists to run *unattended*, so it is
shaped entirely around not being trusted with a thread's ambient state: it resolves work through
`AutopilotResolver.findAllEngaged()` (an installation-wide sweep that reads no request scope) and
it enforces engagement in three places — the `findAllEngaged()` filter at entry, a re-read in
phase 1 `settle()`, and a per-item re-check in phase 3 `start()` — so an emergency stop takes
effect mid-pass.

`AutopilotController` also exposed a manual "Run tick now" button on `POST /api/v1/autopilot/tick`,
and it called the *same* `service.tick()`. In core (one Autopilot per installation) that was
invisible. Downstream, where each organisation owns its own Autopilot, it was wrong on both axes
the button cares about:

- **Scope.** `findAllEngaged()` returns every organisation's engaged Autopilot. A per-org request,
  authorised by `@orgSecurity.canOperate()` for the caller's org alone, therefore ticked — stamped
  `last_tick_at`, settled outcomes, and started agent pods on — every *other* organisation's
  engaged Autopilot too. The authorisation check and the action disagreed about whose resource was
  being touched.
- **Engagement.** Because `tick()` gates on engagement in all three places above, a manual tick on
  a *disengaged* Autopilot did nothing at all — no pass, no error — while the endpoint still
  returned 200 and the UI toast still said "Autopilot tick run". The only distinctive value a
  manual tick has is running one pass *on demand while the Autopilot is not running automatically*;
  gated on engagement, the button was a no-op in exactly the state where it is the only way to make
  progress (paused work, or recovery after the failure breaker tripped).

## Decision

**The manual tick is a distinct pass — `AutopilotService.tickCurrentScope()` — that is org-scoped
and independent of both the engaged flag and the failure breaker. The scheduler's `tick()` is
unchanged.**

- **Org-scoped through the resolver seam, not a new one.** `tickCurrentScope()` resolves the
  caller's Autopilot with `AutopilotResolver.forCurrentScope()` — the request-scoped half of the
  seam the resolver already declares — and ticks that one id. Core resolves its singleton;
  downstream, the multi-tenant resolver resolves the caller's org row. The endpoint is org-scoped
  because that resolver is, with no autopilot-specific tenancy logic added to the controller. The
  scheduler keeps using `findAllEngaged()`, which is correct for a background sweep and wrong only
  for a per-request action.
- **Engagement and the breaker gate the scheduler, not a human.** A `Trigger` (`SCHEDULED` /
  `MANUAL`) is threaded through `tickOne` → `runPass` → `settle`/`start`. Each of the three
  engagement gates and the two breaker-abort points is guarded `trigger == SCHEDULED && …`, so a
  `MANUAL` pass runs the full pass — settle, plan, start — whatever the engaged flag says. A
  human clicking a button is a deliberate, already-rate-limited, authorised action; engagement and
  the three-strikes breaker are protections for *unattended* operation.
- **A manual pass still counts outcomes and still lets the breaker disengage — it is only never
  aborted by it.** `settle()` on a `MANUAL` trigger still calls `applyBreaker`, so a failing run is
  still counted and the Autopilot is still disengaged for the scheduler that follows; what changes
  is that the tripped breaker returns `PROCEED` instead of `BREAKER_TRIPPED`, and the mid-pass
  `recordFailure` no longer breaks the start loop. The failure budget is spent and reported exactly
  as before; only *this* human-requested pass is allowed to finish.
- **`tickCurrentScope()` carries no `@Transactional`, exactly like `tick()`.** It runs the same
  four short transactions plus the tick lease, so an ambient transaction would merge them into the
  one long transaction that structure exists to remove. It asserts no active transaction on entry,
  and the reflective guard in `AutopilotServiceTest` that requires every *other* public method to
  be `@Transactional(REQUIRED)` exempts it by signature alongside `tick()`.

## Consequences

- `last_tick_at` is no longer a clean "was a full pass run" signal for a single Autopilot:
  `engage()` stamps it too (it resets the healthy-tick clock). The org-isolation test therefore
  asserts org B's stamp is *unchanged* by org A's tick, not that it is null — a test that a global
  tick fails and the scoped one passes.
- The endpoint is now the only production entry that runs a pass on the request thread with a
  `MANUAL` trigger; `AutopilotScopeBinder` already anticipated a request-thread caller (it restores
  the prior scope rather than clearing), so no binder change was needed.
- A future "tick every org" administrative action, if one is ever wanted, is a *different* endpoint
  authorised at a higher level — it must not be folded back into the per-org `POST
  /api/v1/autopilot/tick`, which is what conflating the two produced in the first place.
