# Contributing to ChorusKube

Thanks for your interest in contributing. This is the minimal dev-setup guide.

## Running the stack

ChorusKube runs as a Docker Compose stack (Docker + Docker Compose v2 required):

```bash
./scripts/setup.sh   # optional: configure Claude/GitHub secrets (all optional)
./scripts/up.sh      # build + start, wait until healthy  (docker compose up --build --wait)
./scripts/down.sh    # tear down + remove volumes          (docker compose down -v)
```

Then open http://localhost:33000. The stack boots with zero configuration, so
`setup.sh` is only needed for real AI runs. See [QUICKSTART.md](QUICKSTART.md#run-a-real-ai-workflow)
for the full port map and how to supply a `CLAUDE_CODE_OAUTH_TOKEN` to run real
AI nodes.

## Components and per-component commands

The repo has three buildable components. Each can be built and tested on its own
for fast iteration:

| Component    | Location        | Build              | Test              |
|--------------|-----------------|--------------------|-------------------|
| API server   | `api-server/`   | `./gradlew build`  | `./gradlew test`  |
| Orchestrator | `orchestrator/` | `go build ./...`   | `go test ./...`   |
| Web UI       | `web-ui/`       | `npm run build`    | `npm run test`    |

- **api-server** — Java 25, Spring Boot, Gradle. (Run `./gradlew build` from
  `api-server/`.)
- **orchestrator** — Go 1.25, Temporal.
- **web-ui** — TypeScript, React, Vite. Run `npm ci` first to install deps.

The repo root is also a Gradle build that includes all three, so the same suites are
addressable from one place as `./gradlew :api-server:test`, `:orchestrator:test` and
`:web-ui:test` — the Go and npm ones are wrapped, not reimplemented. Run them
per-component while iterating on one component; run them from the root when you want
all three, since the root runs them in parallel. See
[End-to-end tests](#end-to-end-tests) for what the root `test` task covers.

## End-to-end tests

`./gradlew test` at the repo root is the entrypoint for both the fast gate and the
full regression:

```bash
./gradlew test         # unit suites only — api-server + orchestrator + web-ui, in parallel
./gradlew test -Pe2e   # the above, then the whole stack chain
```

**A bare `./gradlew test` does not boot the stack.** That is a deliberate break from
the old `scripts/e2e.sh` (now deleted), where the fast gate and the heavy one were
the same command and you could not ask for only the first. Putting the expensive half
behind a property makes the default the cheap thing, and it converges this repo on the
shape the closed sibling repo already uses — the same two commands now mean the same
two things in both.

The three unit suites run **concurrently**, not one after another: they share no
state (separate processes, separate toolchains), so the unit stage costs as long as
its slowest suite rather than the sum of all three. That comes from
`org.gradle.parallel=true` in the root `gradle.properties`; drop it and the stage
silently serializes while still passing.

With `-Pe2e`, Gradle then runs the end-to-end chain as separate tasks:

| Task | What it does |
|------|--------------|
| `e2eImages` | Build the agent and application images |
| `e2eStackUp` | `docker compose up`, then wait for health |
| `e2eSmoke` | API smoke checks against the live stack |
| `e2eSeed` | Load WireMock stubs and seed test data |
| `e2ePlaywright` | Drive the Playwright suite end-to-end |

The stack it boots is auth-free and listens on `2xxxx` ports (web UI on 23000, API on
28080) — separate from the local development stack on `3xxxx` ports, so the two can
coexist. The specs drive real runs through Temporal, the orchestrator, object storage
and an agent container. Requires Docker (same requirement as the dev stack), plus
Java 25 + Go 1.25 + Node 22 on the host for the unit stage.

Teardown is a Gradle **finalizer** on `e2eStackUp` rather than the last link in the
chain. A finalizer runs once the task it finalizes has completed, including when the
build is already failing — so the containers come down exactly when they went up, and
a failure in `e2eSmoke` no longer leaks a stack that a trailing chain step would have
been skipped past. Opt out with `-Pe2eNoTeardown` when you want to inspect a failure;
the stack is then yours to clean up:

```bash
./gradlew test -Pe2e -Pe2eNoTeardown   # leave the stack up — you must tear it down
./scripts/e2e-down.sh                  # stop containers
./scripts/e2e-down.sh --volumes        # stop and wipe data volumes
```

If a stack is already up (e.g. after `-Pe2eNoTeardown`), re-run just the Playwright
specs against it — one task, two entry paths:

```bash
./gradlew e2ePlaywright         # from the repo root
cd web-ui && npm run test:e2e   # equivalent, straight from the web-ui tree
```

### What every build reports

Each build prints a per-task timing table when it finishes — on success **and** on
failure — breaking the run into each suite's init / build / test phases plus each e2e
phase, with each one's share of the wall clock. The same numbers are written alongside
it as a TSV. Nothing consumes this programmatically; the point is that "the suite got
slower" is answerable from the log of the run that was slow, instead of by re-running
it with a stopwatch. A phase with no measurement renders as `-`, because the timing
report must never be the reason a build fails.

Test reports keep their per-component default locations unless you say otherwise.
`-Dtest.reports.dir=<absolute path>` redirects all of them into a single tree, with
each suite nested under it:

```bash
./gradlew test -Pe2e -Dtest.reports.dir=/tmp/reports/choruskube
```

That value is a per-**repo** root, not one suite's leaf — hence the repo name on the
end. An agent run can collect several repos' reports into one output directory, and
more than one of those repos has an `api-server` component, so without the repo infix
whichever finished second would silently overwrite the first. Leave the property unset
and nothing is redirected, which is why an ordinary local run looks exactly as it
always did.

### Related, but not the same check

`scripts/oss-smoke.sh` boots the canonical stack from **published** images and drives
one feature-dev run end to end; `./gradlew test -Pe2e` builds everything from source.
Use the e2e suite to validate a change that crosses a component boundary before
declaring it complete, and `oss-smoke.sh` to validate that what was published actually
works.

In CI, the `E2E` workflow (`.github/workflows/e2e.yml`) runs `./gradlew test -Pe2e` —
unit suites included — on every PR to `main`, and nowhere else: it is a pre-merge gate,
not a post-merge one. Re-running it on `main` would re-verify content the PR already
proved green while occupying a runner that a live PR check needs. Use
`workflow_dispatch` if something reaches `main` outside a PR and you want the suite
over it.

### Running the Playwright suite in parallel

The Playwright suite (`web-ui/e2e/specs/`) is serial by default — safe for local dev,
where nothing else is competing for the one shared stack instance. Opt into multiple
workers with the `-Pworkers` property or the `E2E_WORKERS` environment variable; both
reach the same `--workers` flag, so use whichever fits the invocation:

```bash
./gradlew test -Pe2e -Pworkers=4               # full run, 4 Playwright workers
./gradlew e2ePlaywright -Pworkers=4            # against a stack already up
cd web-ui && E2E_WORKERS=4 npm run test:e2e    # same, straight from the web-ui tree
```

Every spec that creates a named resource (a Run/Epic/Task title, a GitRepo, a
RepoGroup) derives that name from `uniqueName()` (`web-ui/e2e/helpers/api-client.ts`)
so concurrent workers never collide over the one shared backend stack — see that
file's doc comment before adding a new spec with a static name literal.

Names are only half of it: workers also share the org-wide list endpoints, the board's
query cache and the `roadmap-items` topic, none of which `uniqueName()` touches. A spec
that asserts over a list, or that drives a drag, has more rules to follow — they're in
[`web-ui/e2e/PARALLELISM.md`](web-ui/e2e/PARALLELISM.md), along with the flakes that
produced them. Read it before adding either kind of spec.

CI (`.github/workflows/e2e.yml`) runs the suite as a single `e2e` job — one runner pod,
one Compose stack — and sets `E2E_WORKERS` on it. That job's name is the required status
check; renaming it detaches the branch-protection rule, which then waits forever on a
check that is never reported.

Splitting the suite across multiple runner pods was tried and removed. Each pod re-pays the
whole non-Playwright preamble (image builds, stack boot, the full unit stage), so it buys
parallelism at a fixed cost in minutes, and the runner pool admits few enough concurrent
runners that the extra pods queued into a second wave rather than starting sooner. Workers
are the cheap axis — one more process against a stack that is already up.

The trade-off workers carry instead is a shared backend: raising `E2E_WORKERS` puts more
concurrent load on one api-server, PostgreSQL, Temporal and Docker daemon, so the ceiling
is backend contention rather than runner capacity. Raise it against measured run times, and
keep the `uniqueName()` discipline above — every added worker widens the window in which
two specs can see each other's rows.

## Sign your commits (DCO)

This project uses the [Developer Certificate of Origin](https://developercertificate.org/).
Every commit must be signed off, certifying you have the right to submit it under
the project's license:

```bash
git commit -s -m "your message"
```

The `-s` flag adds a `Signed-off-by: Your Name <your@email>` line. Commits
without a sign-off will not be accepted.
