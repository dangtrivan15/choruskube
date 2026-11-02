# Design work counts as started in the Autopilot's ordering

**Status:** current

## Context

[2026-10-31---02](2026-10-31---02-autopilot-finishes-an-epic-before-the-next.md) ranked an Epic or
Story as started when one of its Tasks was past `backlog`. A Task starts only under a Story with an
approved spec, so a Story whose spec was just approved, or whose design is in flight, had no started
Task. Right after the Autopilot designed Story A, it preferred the Tasks of a Story B that happened
to have a non-backlog Task, and A's Tasks sat idle.

## Decision

**A Story with an approved spec or an open design run is started, and so is its Epic.** An open run
includes a completed one whose spec is not yet recorded, the same rule that stops the Story being
designed again. A Story whose only design run failed or was cancelled is not started: it is held for a
human, not in progress.

**The facts come from the design sweep.** It already reads spec versions and open design runs to
find designable Stories, so ordering costs no extra query on the 10 s tick.

When every Story in an Epic is designed they tie on started, and `created_at` then `id` decide.

## Supersedes

From [2026-10-31---02](2026-10-31---02-autopilot-finishes-an-epic-before-the-next.md), the
definition "started means one of the container's Tasks is past `backlog`" and the claim that the set
needs no read beyond the Task rows. A Task past `backlog` still counts.

## Rejected

- **A separate `designStates` read after the sweep.** It returns the same facts for the same Stories,
  at the cost of two more queries on every tick.
- **Count an approved spec only, not an open design run.** The Story under design yields no frontier
  item, so for the whole first design its Epic would rank as untouched and the Autopilot would move
  to an older Epic's design.
- **A new ranking key for designed Stories, beside started.** Started already stands in for "the
  user is mid-way through this", and an extra key would make the same idea read two ways.
