# The e2e stack runs Temporal's dev server and packages a host-built api-server jar

## Status

current — amends (does not supersede)
[2026-09-21---01-e2e-memory-fits-constrained-test-node.md](2026-09-21---01-e2e-memory-fits-constrained-test-node.md),
whose heap bound and per-service caps stay in force.

## Context

Memory reserved for a test runner is memory no other workload can use, so the e2e suite's footprint
is now a cost to minimise, priced against how faithfully the stack still exercises our contracts —
not a fixed parity rule. Measured on a memory-capped runner, the pod's resident set peaks at about
the same height in three separate phases, so no single change lowers the peak; each phase needs its
own cut:

| phase | what peaks |
|---|---|
| unit stage | the api-server test JVM and vitest's workers, overlapping under `org.gradle.parallel` |
| `e2eStackUp` | `compose up --build` running the api-server's Gradle build *inside Docker* concurrently with the Go and web-ui image builds (~2.6G in the Docker daemon, against ~1.2G for the running stack) |
| Playwright | the running stack plus an idle Gradle daemon still holding its build-time heap |

## Decision

1. **Temporal runs its single-binary dev server** (`temporalio/temporal`, `server start-dev`,
   SQLite file) instead of `temporalio/auto-setup` on the shared PostgreSQL, capped at 192m with
   `GOMEMLIMIT`. It is the same server and gRPC API, not a mock: every call our code makes — workflow
   start, per-execution signals, terminate, activity heartbeats and Heartbeat/StartToClose timeouts,
   timers, async completion and heartbeat by ID — was exercised against it.
   What changes is Temporal's own persistence (SQLite, one history shard), which no code of ours
   touches. Measured ~50–70MiB idle and ≤~110MiB after 1000 workflows; `auto-setup` was capped at
   768m. It also moves e2e from the deprecated `auto-setup` image's server 1.25 to a current server.
2. **The api-server jar is built on the host and only packaged in Docker** (`api-server/Dockerfile.e2e`,
   built by `scripts/e2e-up.sh` before `compose up`). This removes the in-Docker Gradle build from
   the concurrent image builds. The published image still builds from `api-server/Dockerfile`; the
   e2e image shares its runtime stage, so the cost is that e2e no longer exercises the Dockerfile's
   build stages — image publishing does.
3. **`:web-ui:test` runs after `:api-server:test`** (`mustRunAfter`): the two largest unit-stage
   consumers no longer overlap. Costs a minute or two of wall time.
4. **The Gradle daemon returns idle heap** (`-XX:G1PeriodicGCInterval=15000` in
   `org.gradle.jvmargs`), so it does not hold its build peak through the stack and Playwright phases.

## Rejected

- **Serialising the image builds instead of host-building the jar.** It removes the overlap but keeps
  a second Gradle JVM inside Docker (still ~1G there) and makes the stack step slower.
- **Temporal's time-skipping test server.** It does not implement the full frontend API
  (`DescribeTaskQueue` is missing), and a long-timer history poll crashed the Go SDK client.
- **Tuning `auto-setup` / `temporalio/server` on PostgreSQL.** More moving parts (schema job, pools,
  shard count) for a saving the dev server already beats.

## Not verified until a full `test -Pe2e` run

The dev server under the complete Playwright suite, and the per-phase peak reductions themselves:
the figures above are from component measurements, not a whole-suite run.
