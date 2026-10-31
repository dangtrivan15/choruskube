# The Autopilot finishes an Epic before it starts the next one of equal rank

**Status:** current

## Context

The Autopilot ranked its READY frontier by Epic priority, then Story priority, then target dates,
then Task `created_at`. It read Task priority not at all. With three priority levels, several Epics
tie on priority, and when none of them is dated the deciding key was each Task's own age. Tasks are
written Epic by Epic but rarely started that way, so the Autopilot alternated between equal Epics
one Task at a time. Users worked around it by numbering Epic titles, which nothing read.

Epic affinity, applied after the sort, was meant to keep work inside the Epic already in flight. It
fired only for an Epic with a live agent pod, so it never fired at `maxParallel = 1`: the one slot
is occupied whenever such a pod exists. At `maxParallel ≥ 2` it moved every Task of the in-flight
Epic ahead of the whole frontier, a higher-priority Epic's included.

## Decision

**The comparator picks an Epic, then a Story inside it, then a Task inside that.** Each Epic is
ranked by priority, target date, whether it is started, `created_at`, and its id, before any Story
or Task key is consulted. Each Story is ranked the same way within its Epic, and each Task by
priority, `created_at` and id within its Story. Every level ends on its own id, so a tie at one
level never falls through to a key from the level below.

**Started means one of the container's Tasks is past `backlog`.** The frontier sweep already loads
every Task of every candidate Epic, so the set costs no extra query. It holds while a run is parked
on a human gate and after a run completes, which a live-pod test could not.

**A target date ranks above started.** A date is the user's explicit signal; started is inferred
from progress.

**Task priority is the first key inside a Story.**

**Epic affinity is removed.** Started subsumes it below priority instead of overriding priority.

**When the chosen Epic has nothing READY, the Autopilot starts the next Epic's Task** rather than
holding the slot idle. At `maxParallel = 1`, waiting would make throughput equal to human review
latency.

## Supersedes

From [2026-09-06---03](2026-09-06---03-autopilot-parallelism-counts-scope-not-attribution.md), the
note that epic-affinity ordering reads the Autopilot's own runs: ordering now reads no runs.

## Rejected

- **Add Task priority as a third priority key and change nothing else.** Equal Epics still
  interleave, because the tie still falls through to Task age.
- **A manual Epic order maintained by the user.** It needs a new column and a reordering UI.
  Started-then-oldest already matches the order users were writing into titles. Revisit if that
  stops holding.
- **Keep the slot idle until the focused Epic unblocks.** Stricter focus, but the Autopilot would
  stop every time a run parks on a human.
