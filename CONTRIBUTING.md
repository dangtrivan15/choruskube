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

## End-to-end tests

`./scripts/e2e.sh` is the full regression harness. It first runs the per-component
unit suites (`api-server` `./gradlew test`, `orchestrator` `go test ./...`, `web-ui`
`npm run test`) so a unit regression fails fast, then boots an auth-free Docker
stack on `2xxxx` ports (web UI on 23000, API on 28080) — separate from the local
development stack on `3xxxx` ports so the two can coexist — runs API smoke checks,
loads WireMock stubs, seeds test data, then drives the Playwright suite end-to-end
through Temporal, the orchestrator, object storage, and an agent pod. Requires
Docker (same requirement as the dev stack), plus Java 25 + Go 1.25 + Node 22 on the
host for the unit step.

```bash
./scripts/e2e.sh                # full run: unit → up → smoke → Playwright → tear down
./scripts/e2e.sh --no-teardown  # leave the stack up (useful for debugging failures)
```

If the stack is already up (e.g. after `--no-teardown`), you can run just the
Playwright specs against it:

```bash
cd web-ui && npm run test:e2e
```

`e2e.sh` now supersets the per-component unit tests (which remain the fast-iteration
path — run them directly while developing; the api-server/orchestrator/web-ui suites
now also run concurrently as background subprocesses within `e2e.sh` itself, so the
unit-test stage takes as long as its slowest suite rather than the sum of all three).
It is also distinct from `scripts/oss-smoke.sh` (boots from published images,
exercises one feature-dev run end-to-end). Use `e2e.sh` to validate changes that cross
a component boundary before declaring them complete. In CI, the `E2E` workflow
(`.github/workflows/e2e.yml`) runs this whole harness — unit suites included — on
every PR and push to `main`.

To tear down the e2e stack manually:

```bash
./scripts/e2e-down.sh           # stop containers
./scripts/e2e-down.sh --volumes # stop and wipe data volumes
```

### Running the Playwright suite in parallel

The Playwright suite (`web-ui/e2e/specs/`) is serial by default — safe for local dev,
where nothing else is competing for the one shared stack instance. Opt into multiple
workers with `E2E_WORKERS`:

```bash
E2E_WORKERS=4 ./scripts/e2e.sh                 # full run, 4 Playwright workers
cd web-ui && E2E_WORKERS=4 npm run test:e2e    # against a stack already up
```

Every spec that creates a named resource (a Run/Epic/Task title, a GitRepo, a
RepoGroup) derives that name from `uniqueName()` (`web-ui/e2e/helpers/api-client.ts`)
so concurrent workers never collide over the one shared backend stack — see that
file's doc comment before adding a new spec with a static name literal.

CI additionally shards the suite across independent runner pods (`.github/workflows/e2e.yml`),
each running `E2E_WORKERS` workers against its own stack instance. `SHARD_INDEX` /
`SHARD_TOTAL` (both unset locally) select the shard's slice of specs and, combined
with the worker index, keep `uniqueName()` collision-free across shards too:

```bash
SHARD_INDEX=1 SHARD_TOTAL=4 E2E_WORKERS=2 ./scripts/e2e.sh
```

The CI workflow's `e2e` job (not the per-shard `e2e-shard (N)` jobs) is the intended
required status check — it's the one job whose name doesn't vary per matrix leg, and
it fails if any shard failed.

## Sign your commits (DCO)

This project uses the [Developer Certificate of Origin](https://developercertificate.org/).
Every commit must be signed off, certifying you have the right to submit it under
the project's license:

```bash
git commit -s -m "your message"
```

The `-s` flag adds a `Signed-off-by: Your Name <your@email>` line. Commits
without a sign-off will not be accepted.
