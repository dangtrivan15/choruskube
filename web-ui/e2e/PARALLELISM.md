# E2E parallelism: keeping the suite correct under concurrent workers

`.github/workflows/e2e.yml` runs the Playwright suite with `E2E_WORKERS: 4`, and
`playwright.config.ts` sets `fullyParallel: true`. Four workers drive **one** Compose
stack — one api-server, one PostgreSQL, one Temporal — and, because `fullyParallel`
distributes individual tests rather than whole files, two tests from the same spec file
can run at the same time in different workers.

This file records why four tests flaked under that setup, what was done about it, and
the rules a new spec has to follow. Read it before adding a spec that asserts over a
list, or that drives a drag or a hover.

## What `uniqueName()` does and does not cover

`uniqueName()` (`helpers/api-client.ts`) appends a worker index and a monotonic suffix to
a resource **name**:

```
uniqueName("cg-reject")  →  "cg-reject-w2-msgy3ut60"
```

That prevents name collisions. It does not isolate anything a name isn't the key to:

| Shared resource | Consequence |
|---|---|
| Org-wide list endpoints (`GET /api/v1/epics`, `/git-repos`) | Any spec's create or delete changes what every other spec reads |
| The board DOM + its react-query cache | A refetch re-renders and re-orders cards under a running test |
| `/topic/roadmap-items` | One event invalidates `["epics"]`, `["stories"]`, `["tasks"]` in every open page |

## The three failure modes

**1. Assertions over an org-wide list.**

```
snapshot list ───── act ───── re-read list ───── assert
                     ▲
        another worker creates or deletes HERE
```

`toBe(equal)` breaks on a concurrent **create**; `toBeGreaterThan` breaks on a concurrent
**delete**. No absolute-count assertion over a shared list holds under concurrency in
either direction. Nor does an ID-set diff — `after.every(e => beforeIds.has(e.id))` trips
on any create in the window just as the count does. Scoping, not a cleverer comparison,
is what fixes this.

**2. A coordinate-based drag interrupted by a re-render.**

```
boundingBox() ─────────────────── mouse.down() → moves → mouse.up()
       ▲
  STOMP event → invalidate ["epics"] → list re-renders / re-orders HERE
       └→ pointer sequence lands on a moved or replaced node → no drop → no PATCH
```

Playwright's auto-waiting covers individual locator actions. A hand-rolled pointer
sequence opts out of it, so nothing re-checks the card between measuring and pressing.

**3. A hover-triggered assertion interrupted by a re-render.**

```
hover() ─────────────────────── wait for Tooltip open → assert content
    ▲
STOMP event → invalidate ["epics"] → marker re-renders at a new x  HERE
    └→ a CSS-transform move alone doesn't re-fire mouseenter/mouseleave → Tooltip never opens
```

Unlike the drag case, Playwright's `hover()` itself auto-waits and is locator-based, not
coordinate-based — the problem isn't the hover call, it's that a single hover-then-assert
only samples one moment. If the element's on-screen position (or the DOM node itself, on a
full re-render) moves out from under an already-resting cursor before the assertion
observes the open Tooltip, the wait times out even though nothing about the interaction
itself was wrong.

## What the suite does about it

**Per-worker software project.** The `workerRepo` fixture (`fixtures/index.ts`) mints one
GitRepo + RepoGroup per worker, fetch-or-create, keyed on `workerInfo.parallelIndex`.
Every spec that needs "a project to hang an Epic off" takes `workerRepo` and passes
`workerRepo.gitRepo.id`. Because a worker runs one test at a time, a per-worker project is
already full isolation for concurrent tests — no per-run project is needed.

**Scoped list assertions.** `api.listEpicsForProject(id)` narrows the org-wide page to one
software project. Any count or ID-set assertion goes through it. A materialized Epic
inherits its run's project via `InternalRunService#createEpic(runId, …)`, so gate specs
can scope the same way by passing `software_project_id` as a run input.

**Re-entrant drag.** `RoadmapBoardPage#dragCardToColumn` re-measures the card immediately
before `mouse.down()`, abandons the attempt if it shifted, and retries the whole gesture
until the card lands.

**Re-entrant hover.** `RoadmapTimelinePage#hoverToRevealPreview` wraps the hover plus its
follow-up assertions in `expect(...).toPass(...)`, so a failed attempt re-hovers (re-locating
the marker at its current position) rather than re-asserting against a stale one.

**Seeded-repo filter.** `GET /api/v1/git-repos` sorts by `url` ascending, and worker repos
live under `https://example.invalid/e2e-worker/` — which sorts **ahead** of the seeded
`https://github.com/e2e-test/…` rows. So `listGitRepos().content[0]` is whichever worker
happened to materialize its fixture first. Specs needing the seeded set specifically call
`seededRepos()` (`fixtures/index.ts`); nothing indexes the raw list any more.

## Rules for a new spec

- Name every created resource with `uniqueName()`.
- Take `workerRepo` rather than reaching into `listGitRepos()`.
- Never assert an absolute count — or an ID-set diff — over an unscoped list.
- Need the seeded repos? `seededRepos(page.content)`, never `content[0]` or `.slice(0, 2)`.
- A hover (or any single-shot interaction) that asserts on something appearing after a delay
  — a Tooltip, a debounced fetch — belongs in `expect(...).toPass(...)`, re-issuing the
  interaction each attempt, not a single `hover()`/`click()` followed by one assertion.

## Why not serialize instead

Nine spec files mutate the shared Epic/Story/Task set: `blocking-chain`,
`multi-repo-epic`, `roadmap`, `roadmap-board`, `roadmap-candidate-gate`, `roadmap-graph`,
`run-lifecycle`, `story-board`, `task-board`. Serializing them means a `workers: 1`
Playwright project holding all nine, since serialization protects a test only against
members of its own group — a mutator left outside still perturbs the shared org.

Measured against a 4-worker run, that group is ~8.3m of test time versus a ~5.1m
Playwright stage today, so it would have cost roughly +3.2m on every run — and the group
is a hand-maintained file list that nothing enforces, so a new mutating spec silently
reopens the hole. (That figure was summed over a ten-file group that also included
`human-gates`, which touches no roadmap entity at all; treat it as an upper bound.)
Scoping the assertions costs nothing per run and leaves them correct on their own terms
rather than correct only while a parallelism policy holds.

## Playwright mechanisms, checked against the pinned version

Pinned: `@playwright/test` ^1.58.2.

| Mechanism | Available | Scope |
|---|---|---|
| **Named test locks** — `test('…', { lock: 'x' }, …)` | ❌ **No.** Absent from every published release: the shipped type definitions for 1.58.2 and for 1.62.1 (latest stable) contain no `lock` option — every textual match is part of `block`/`blockquote`. Documented only on upstream `main` | Would serialize just the lock-sharing tests, across files *and* projects |
| **`TestProject.workers`** | ✅ Yes, 1.58.2 | Limits concurrency **within** one project; projects still run concurrently with each other |
| **`test.describe.serial()`** | ✅ Yes | Within a single **file** only — no cross-file exclusion |
| **`fullyParallel: false`** | ✅ Yes | Makes tests within a file sequential; files still run in parallel across workers |

If named locks ever ship, `{ lock: 'roadmap-items' }` on the mutating specs would be a
narrower mechanism than a serial project — but it needs a version bump and re-verification.

## Diagnosing a flake

Failure modes here are schedule-dependent; a single green run is not evidence of a fix.

- `E2E_WORKERS=4` (or higher) widens the overlap window.
- Set `trace: "retain-on-failure"` in `playwright.config.ts`. The default `on-first-retry`
  records the **retry**, so a flake that passes on retry keeps a trace of the passing
  attempt and none of the failure.
- Failure screenshots are captured after the test body completes, so a `finally` block
  that deletes fixtures runs first and the screenshot shows post-cleanup state. Screenshot
  inside the `catch`, or move cleanup to an `afterEach`, when you need the real picture.
- `tsconfig.e2e.json` is not referenced from the root `tsconfig.json`, so neither
  `npm run build` nor `npm run lint` typechecks this directory. Run
  `npx tsc -p tsconfig.e2e.json --noEmit` by hand after touching a fixture or page object.
