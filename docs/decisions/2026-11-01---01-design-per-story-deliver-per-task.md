# Design once per Story, deliver once per Task; conflicts escalate to the Story; revisions are new runs

**Status:** current. Builds on [2026-10-11---01](2026-10-11---01-approval-rechecks-against-current-main.md),
[2026-10-31---01](2026-10-31---01-spec-gate-reapplies-a-revised-approval.md) and
[2026-10-31---02](2026-10-31---02-autopilot-finishes-an-epic-before-the-next.md) without amending them.

## Context

Every Task ran the whole Feature Development template: draft a spec, review it, have a human approve
it, then implement, review the code, test, and get final approval. A Story with N Tasks paid for N
specs, and most of what those specs decide belongs to the Story.

Measured on the 54 Task runs that completed between 2026-09-17 and 2026-11-01:

- **The spec cost more than the code.** Writing and reviewing it took a median of 44 minutes of
  agent time; implementing and reviewing the code took 36. Spec Review sent the spec back about
  twice per run.
- **The spec was paid N times.** 48 of the 54 runs were Tasks in a Story with more than one Task.
- **Most send-backs at the human spec gate were not about design.** 19 of 24 were drift re-checks,
  mostly because a sibling Task in the Story had merged. The rest were Story-level calls ("hard
  cutoff, no backward compatibility", "include the other repo's change so the spec is coherent").

The spec already had a seam along this line. Its specification half is design written for humans
and is Story-shaped. Its implementation plan is file-level steps written for agents and is
Task-shaped.

## Decision

**Design happens once per Story. Delivery happens once per Task. The plan is written at the last
responsible moment**, against the main the Task will actually change.

### Three templates

- **Story Design** (`story-design`): Draft Story Spec, then Story Spec Review (self-looping),
  then the human gate Approve Story Spec, with a Supervisor hub. It runs against a Story, not a
  Task, so only these runs carry `workflow_run.story_id`; Task runs keep `task_id`.
- **Task Delivery** (`task-delivery`): Analyze, then (when needed) Task Spec Review and Approve Task
  Spec, then Implement, Code Review, Test and Final Approval. Implement and Code Review prompts
  and Final Approval's configuration come from one shared place both seeders read. Each template
  keeps its own `CURRENT_VERSION` and tripwire test, so a change to shared text bumps both.
- **Feature Development** is unchanged and keeps serving runs started without a Task, and runs
  already in flight.

Every Task started from the roadmap, by a human or by the Autopilot, runs Task Delivery. Starting
one whose Story has no approved spec is refused with a 409 (`Cannot start this Task: Story spec not
approved yet`). Nothing designs a Story as a side effect of starting a Task.

### The Story spec

`story_spec.md` keeps the specification half of today's spec, minus the file-level Expected Changed
Files, and adds two sections: **Tasks** (each slice of the work maps to one Task, existing or
proposed, and states which spec sections it realises) and **Revisions** (an append-only log, one
entry per revision run).

- **Approval materializes the breakdown.** The gate reuses the `roadmap_extension` mode, with the
  triggering Epic resolved through the run's Story, rather than a mode that confines a proposal to
  the Story's own children: a Story spec's future-work caveats are proposed at this approval, and
  they belong outside the Story. The human sees the whole proposal at the gate. Existing Tasks are
  never edited or removed; a mismatch between one and the spec is a caveat for the human. There is no repo-drift re-check at
  this gate: the spec is design-level, and each Task re-plans against current main.
- **Versions live in the database.** `story_spec_version` holds the full approved text per Story,
  numbered from 1. Every approved Story Design run, initial or revision, writes exactly one row,
  so the version rises on every completed revision *even when every conflict "holds"*, and "is
  there something newer than I planned against?" is one integer comparison. The object store is
  not used: an agent can only fetch its own run's prefix, and a row is a plain read for the UI.
- **A reconciler writes the row, not the gate.** `StorySpecReconciler` records a version when it
  finds a Story Design run in `completed`. A run only completes after its terminal gate has
  finalized, which is after materialization has run, so the version never precedes it: a crash
  between the two leaves the Story with no approved spec, and none of its Tasks startable, until
  the next sweep records it. One idempotent sweep survives that crash without a hook in each
  finalize path. `choruskube.story-spec.enabled=false` therefore leaves every undesigned Story
  unstartable.
- **A stopped materialization still records its version.** That ordering is not a completeness
  check. When materialization stops partway, the gate finalizes with a note, the run completes, and
  the version is recorded without reading the outcome. The Tasks already created become startable
  and the missing ones do not exist, so the human adds them by hand; the gate already shows the stop
  as a note.
- **Deleting a Story still works.** `workflow_run.story_id` is `ON DELETE SET NULL`, because a run
  carries external side effects and is never cascade-deleted with its Story. `story_spec_version`
  and `story_conflict` cascade from `story`, so removing a Story, or an organization above it in a
  multi-tenant deployment, is not blocked by their rows.
- **A Task run gets a copy at start,** staged as the run input `story_spec.md`, and records
  `workflow_run.story_spec_version`. Releasing a parked run overwrites that input and the column.

### Analyze decides what a Task needs

Analyze always writes `plan.md` (its first line is `Realises: <spec section headings>`) against
current main, then decides exactly one of:

| Decision | Test |
|---|---|
| `planned` | The Story spec plus the Task make every choice a reviewer could reasonably reject. |
| `needs_spec` | **Gap:** the Task needs such a choice and the Story spec does not make it. The bar is a `docs/decisions/` entry: a choice a later reader could not recover from the code. Writes `task_spec.md`, which must not contradict the Story spec. |
| `story_conflict` | **Conflict:** following the Story spec as written would be wrong given current code or a merged sibling, or the spec contradicts itself or the Task. Writes `story_conflict.md`. |

An implicit `escalate` covers a Task that is already done, should be split, or is not this
Story's work. The rubric is a written test with an outside standard because a node judging whether
its own work needs review is judging its own case.

**The server, not the agent, picks which plan Implement reads.** Agent inputs carry no timestamps,
and a stale `task_spec.md` would keep being fed after a later Analyze decides `planned`. An input
group declared `newest_of: [analyze, task_spec_review]` with the alias `plan` resolves to the
outputs of whichever of those nodes completed most recently, under `/workspace/in/plan/`.

**Only Analyze raises a story conflict.** A later node that finds one escalates; the Supervisor
routes to Analyze with guidance. Task Spec Review that finds a Story-wide choice records it as a
note for the human at Approve Task Spec instead of escalating: escalating would loop, because the
run's `story_spec.md` is not refreshed by a Supervisor route.

### A story conflict escalates to the Story; a revision is a new run

A conflict never becomes a local override. The Task parks at the human gate Await Story Revision (it
declares no timeout of its own, so the default seven-day human-gate timer applies; a revision left
unapproved longer fails the Task run into the usual retry path), and the same reconciler moves each
unreleased `story_conflict` along, per Story:

```
open revision run for the Story?            -> wait
else, per unreleased conflict:
  current version > raised_against version  -> RELEASE: overwrite story_spec.md, bump the run's
                                               version, upload guidance, decide the gate `resume`
  current version == raised_against and
  no revision covers it                     -> START one revision covering ALL such conflicts
```

- **Concurrent conflicts batch.** Those raised before a revision starts join it; those raised
  while one is open wait, are released after it, and re-analyze against the new version.
- **A revision is an ordinary Story Design run** with `revision_context` set (the approved spec,
  the conflict reports, any guidance), on the latest template version. It goes through Story Spec
  Review and Approve Story Spec like the first design. A human can start one without a conflict,
  from the Story page, with guidance.
- **Release guidance names the Revisions entries by version and never claims one answers the
  conflict.** Analyze may still find the conflict standing, and then raises it again.
- **A revision that fails or is cancelled** leaves its conflicts unreleased until a human decides
  the Await gate or starts another revision.
- **Decisions carry over, transcripts do not.** The reviser sees the current spec, its Revisions
  log, and the Story-wide review history (review results across the Story's runs, plus each earlier
  approved version's final review file copied in as `prior_review_v<N>.md`), so it does not
  silently reverse an earlier call. Session revival stays off: transcripts are consume-once because
  they may hold credentials redaction missed, and a revived session observed an older main.

### Siblings: readiness and the story-version re-check

- **One readiness definition.** `EpicReadinessAssembler` makes a Story with no approved spec, or
  with an open Story Design run, make its Tasks not startable, with the reason `Story spec not
  approved yet` or `Story spec under revision`. Manual Start, the Autopilot, the ready count and
  the roadmap graph all read it. A run is *open* while active, or while completed but not yet
  recorded as a version; soft-deleted runs never count, so deleting a run that can never be
  recorded is the human escape hatch.
- **Story-version re-check at Approve Task Spec and Final Approval,** declared as
  `story_version_recheck: {"route": "rereview"}` beside `drift_recheck`. While a revision is open,
  an `approved` decision is refused with a 409 naming the revision run. If the Story's version has
  moved past the run's, the approval becomes the `rereview` decision, with guidance naming the new
  Revisions entries and an updated `story_spec.md`; in that round the reviewer's check target is
  those entries. The check also voids a drift-held approval from the asynchronous finalize path, so
  the approver's next approval reroutes.
- **It holds no stamp.** Drift on main re-applies a held approval after the re-review; a design
  change does not. The approver approves again after the re-review. This errs toward more oversight
  and avoids teaching each finalize path a second kind of drift.

### The Autopilot designs Stories forward

Rather than reacting when a Task turns out to be blocked, the Autopilot settles a Story's design
before it reaches the Story's Tasks, in its own Epic, Story ordering
([2026-10-31---02](2026-10-31---02-autopilot-finishes-an-epic-before-the-next.md)).

- A Story is **designable** when it has no approved version and no open design run, and every Story
  or Epic it depends on is done *or has an approved spec*. Design is gated on a blocker's design,
  not its merged code: the next Story's spec builds on the previous one's approved spec, and a
  story conflict catches any later drift.
- A Story is designed only for a Task still to run: it must have no Tasks or a backlog one.
- A design run stamped with the Autopilot's id is counted like a Task run: it occupies a slot while
  active, counts toward `max_awaiting_human` while it waits at Approve Story Spec, and settles into
  the three-failure breaker. How far ahead it designs is bounded by those two existing caps; there
  is no new setting. A revision run inherits the `autopilot_id` of the conflicting Task run.
- **A Story whose newest design run failed or was cancelled, and has no version, is held,** not
  designable, and is reported as held with a reason. Redesigning it every tick would undo a
  human's cancel and retry a broken design until the breaker trips. A human releases it by
  designing it by hand or deleting the run.
- Off-request starts need an org scope in a multi-tenant deployment: `StoryScopeBinder` is the seam
  for starting a Story Design run, and `RunScopeBinder` for deciding a gate on an existing run.

## Consequences

- **The first ticks after deploy burst.** Every existing Story with backlog Tasks becomes a "Design
  Story" item; `max_awaiting_human` bounds the pile of approvals. A failed Feature Development
  run's Task needs its Story designed before it can be re-triggered.
- **Analyze's enforcement is soft.** Whether it applies the rubric well is not testable; read its
  decisions against their plans. Expect mostly `planned`, with `needs_spec` and `story_conflict`
  rare, and watch gate stops and agent time per Task against the numbers in Context.
- **Code Review now sees the Story spec as context.** By default it checks the change against the
  Task's plan; watch for reviews that drift into re-litigating the Story.
- **Shared prompt text couples the two templates' versions.** Accepted as the cost of not forking.

## Rejected

- **Moving the whole workflow to the Story.** One large PR per Story, Implement at risk of its
  time budget, and the Autopilot's Task ordering is lost.
- **A long-lived Story run that revisions re-enter.** It would revise with prompts many template
  versions old, keep Temporal compatibility branches alive for weeks, and hold a concurrency slot.
- **A local Task-spec override on conflict.** Siblings inherit the flawed spec and each
  rediscovers and resolves it differently.
- **A Task-level gate that amends the Story spec.** The Story's own review and approval would never
  see the change.
- **Reviving the spec author's session to talk with the Task.** It breaks consume-once transcripts,
  and the session saw an older main.
- **The spec in the object store under a Story prefix.** A third key layout that runs cannot read
  across anyway.
- **Writing the version in the gate's finalize.** It needs a hook in each finalize path and a
  crash-recovery story; one sweep gives the same ordering.
- **Letting the agent pick the newer `plan.md`.** Inputs carry no timestamps.
- **`always` for the story-version re-check.** An approval would re-apply and merge after an
  AI-only re-review even when the design change altered this Task's code. `if_unchanged` was the
  next candidate; no stamp at all won because the drift machinery is tied to repo bases at many
  touchpoints.
- **AI-only review of a Task spec.** A Task spec exists only when there is a design choice to make,
  and past human pushback at the spec gate was exactly that kind of choice.
- **Opt-in design per Story, or moving existing Stories onto Feature Development.** Both leave two
  Task workflows to maintain.
- **Designing a Story only when one of its Tasks is started.** It idles the slot the Task was
  picked for and hides design work from the ordering.
- **Waiting for a blocker Story to be done before designing.** Safest against stale specs, but it
  serialises dependent Stories end to end. Designing at most one Story ahead is the fallback if
  Task Delivery runs show frequent `story_conflict` decisions traced to designing too far ahead.
