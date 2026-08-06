# E2E: shared-state flakiness under parallel workers

Status: **open, unresolved.** This file records the investigation so the next person does not
have to repeat it. No fix is applied.

## Situation

`.github/workflows/e2e.yml` runs the Playwright suite with `E2E_WORKERS: 4`. All four workers
drive **one** Compose stack — one api-server, one PostgreSQL, one Temporal, one org.

The suite's only isolation mechanism is `uniqueName()` (`e2e/helpers/api-client.ts`), which
appends a worker index and a monotonic suffix to resource **names**:

```
uniqueName("cg-reject")  →  "cg-reject-w2-msgy3ut60"
```

That prevents name collisions between workers. It does not isolate:

| Shared resource | Consequence |
|---|---|
| Org-wide list endpoints (`GET /api/v1/epics`, `/git-repos`) | Any spec's create/delete changes what every other spec reads |
| The board DOM + its react-query cache | A refetch re-renders and re-orders cards under a running test |
| `/topic/…/roadmap-items` | One event invalidates `["epics"]`, `["stories"]`, `["tasks"]` in every open page |
| `listGitRepos().content[0]` | Specs that index into the list pick a different repo as repos are added |

Every failure below sits in that gap.

## Observed failures

| Spec | Assertion | Symptom |
|---|---|---|
| `roadmap-board.spec.ts:137` | `expectCardInColumn(title, "in_progress")` | `element(s) not found` after 10s — the drag never took effect |
| `roadmap-candidate-gate.spec.ts:89` | `expect(after.length).toBeGreaterThan(before.length)` | `Expected: > 5  Received: 5` — net zero, materialization offset by a concurrent delete |
| `roadmap-candidate-gate.spec.ts:136` | `expect(after.length).toBe(before.length)` | `Expected: 5  Received: 8` — three unrelated Epics appeared mid-window |

All three passed on other runs. They are load- and schedule-dependent, not deterministic.

## Two distinct failure modes

**1. Assertions over an org-wide list.**

```
snapshot list ───── act ───── re-read list ───── assert
                     ▲
        another worker creates or deletes HERE
```

`toBe(equal)` breaks on a concurrent **create**; `toBeGreaterThan` breaks on a concurrent
**delete**. No absolute-count assertion over a shared list holds under concurrency in either
direction.

`roadmap-candidate-gate.spec.ts:159-165` already carries a comment explaining why the check
diffs Epic IDs rather than titles — cross-spec interference was anticipated for titles, and the
count assertion on the line above was left in place.

**2. Coordinate-based drag interrupted by a re-render.**

```
boundingBox() ─────────────────── mouse.down() → moves → mouse.up()
       ▲
  STOMP event → invalidate ["epics"] → list re-renders / re-orders HERE
       └→ pointer sequence lands on a moved or replaced node → no drop → no PATCH
```

`roadmap-board.page.ts#dragCardToColumn` measures the card and presses it in separate round
trips. Playwright's auto-waiting covers individual locator actions; a hand-rolled pointer
sequence opts out of it, so nothing re-checks the card between measuring and pressing.

## The closure rule

Serialization protects a test only against members of its own group. A spec left running in
parallel still mutates the shared org, so a group containing only the *failing* specs is not
sufficient — the group must be closed over every spec that writes to the shared Epic/Story/Task
set.

Files that mutate that set (`createEpic`, `deleteEpic`, `createStory`, `createTask`, stage or
status transitions, gate approval/materialization):

```
blocking-chain.spec.ts          roadmap-candidate-gate.spec.ts   story-board.spec.ts
human-gates.spec.ts             roadmap-graph.spec.ts            task-board.spec.ts
multi-repo-epic.spec.ts         roadmap.spec.ts
roadmap-board.spec.ts           run-lifecycle.spec.ts
```

Ten of the suite's spec files.

## Playwright mechanisms (researched against the pinned version)

Pinned: `@playwright/test` ^1.58.2.

| Mechanism | Available | Scope |
|---|---|---|
| **Named test locks** — `test('…', { lock: 'x' }, …)` | ❌ **No.** Not in any published release. Types for 1.62.1 (latest at time of writing) contain zero occurrences of `lock`. Documented only on the upstream `main` branch | Would serialize only lock-sharing tests, across files *and* projects, leaving everything else parallel |
| **`TestProject.workers`** | ✅ Yes, 1.58.2 (`TestProject` interface) | Limits concurrency **within** one project. Projects still run concurrently with each other |
| **`test.describe.serial()`** | ✅ Yes | Within a single **file** only. Playwright assigns whole files to workers, so it gives no cross-file exclusion |
| **`fullyParallel: false`** | ✅ Yes | Makes tests within a file sequential; files still run in parallel across workers |

Note the interaction: because separate projects run concurrently, a `workers: 1` project only
delivers mutual exclusion if the group inside it is closed per the rule above.

## Measured cost of serializing the closure

Summed per-test durations from a 4-worker run:

```
serial group (10 files)   497s   (8.3m)
whole suite              1047s  (17.4m)
remainder ÷ 4 workers     137s   (2.3m)

current Playwright stage           ~5.1m wall
projected, group serialized        ~8.3m wall   (the serial group becomes the critical path)
```

## Options

Each is stated with its tradeoffs; none is applied.

**A. One `workers: 1` project holding the closure.**
Add a project (`testMatch` = the ten files, `workers: 1`, `dependencies: ["setup"]`) and add a
matching `testIgnore` to `chromium`.
*For:* single policy, covers both failure modes, no per-test changes.
*Against:* ~+3.2m on the Playwright stage; the group is defined by a file list that must be
updated whenever a new spec mutates Epics, and nothing enforces that — a missed file silently
reopens the hole.

**B. Repair the individual assertions.**
Replace absolute-count assertions with ones scoped to the run under test, e.g. by giving each
run its own software project and filtering the diff by it (a materialized Epic inherits its
run's software project via `InternalRunService#createEpic(runId, …)`).
*For:* no wall-clock cost; assertions become correct on their own terms rather than correct only
while a parallelism policy holds.
*Against:* addresses failure mode 1 only, leaving the drag untouched. Introducing per-run
projects perturbs `listGitRepos().content[0]`, which `roadmap-candidate-gate.spec.ts:89`
indexes into — that call site would need changing in the same pass.

**C. Make the drag re-entrant.**
Re-measure immediately before pointer-down and retry the gesture until the card lands.
*For:* no wall-clock cost; keeps a genuinely UI-driven drag as the trigger.
*Against:* addresses failure mode 2 only; remains coordinate-based, so it narrows the race
window rather than closing it.

**D. Per-worker data isolation.**
Give each worker its own org via a worker-scoped fixture, so no two workers share a list, a
board, or a topic.
*For:* removes the shared resource itself, so both failure modes lose their trigger and the
closure rule stops applying.
*Against:* test-infrastructure work rather than a patch; provisioning cost per worker; specs
that deliberately assert against fixed seeded fixtures would need reworking.

**E. Lower `E2E_WORKERS`.**
*For:* one-line change; at `1` the shared-state races cannot occur.
*Against:* proportional wall-clock increase (the whole suite is 17.4m of test time); at any
value above 1 the races remain, only rarer — which makes failures less frequent and harder to
reproduce.

**F. Adopt named locks when they ship.**
Track upstream and declare `{ lock: 'roadmap-items' }` on the affected specs once a release
carries the feature.
*For:* the narrowest mechanism — only lock-sharing tests serialize, across files and projects.
*Against:* unavailable today; requires a version bump and re-verification when it lands.

## Reproducing

Failure modes are schedule-dependent; a single green run is not evidence of a fix. Useful knobs:

- `E2E_WORKERS=4` (or higher) to widen the overlap window.
- `trace: "retain-on-failure"` in `playwright.config.ts` — the current `on-first-retry` records
  the **retry**, so a flake that passes on retry keeps a trace of the passing attempt and none of
  the failure.
- Screenshots are captured after the test body completes, so a `finally` block that deletes
  fixtures runs first and the screenshot shows post-cleanup state.
