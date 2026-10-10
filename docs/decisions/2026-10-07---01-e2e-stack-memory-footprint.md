# The e2e stack packages a host-built api-server jar and staggers its memory peaks

**Status:** current — amends (does not supersede)
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

1. **The api-server jar is built on the host and only packaged in Docker** (`api-server/Dockerfile.e2e`,
   built by `scripts/e2e-up.sh` before `compose up`). This removes the in-Docker Gradle build from
   the concurrent image builds. The published image still builds from `api-server/Dockerfile`; the
   e2e image shares its runtime stage, so the cost is that e2e no longer exercises the Dockerfile's
   build stages — image publishing does.
2. **`:web-ui:test` runs after `:api-server:test`** (`mustRunAfter`): the two largest unit-stage
   consumers no longer overlap. Costs a minute or two of wall time; `org.gradle.continue` keeps an api-server failure from
   skipping the web-ui suite.
3. **The Gradle daemon returns idle heap** (`-XX:G1PeriodicGCInterval=15000` in
   `org.gradle.jvmargs`), so it does not hold its build peak through the stack and Playwright phases.

## Rejected

- **Serialising the image builds instead of host-building the jar.** It removes the overlap but keeps
  a second Gradle JVM inside Docker (still ~1G there) and makes the stack step slower.
- **Temporal's time-skipping test server.** It does not implement the full frontend API
  (`DescribeTaskQueue` is missing), and a long-timer history poll crashed the Go SDK client.
- **Temporal's single-binary dev server** (`temporalio/temporal`, `server start-dev`, SQLite file,
  192m cap, `GOMEMLIMIT=96MiB`) in place of `auto-setup`. It is the same server and gRPC API and passed
  a 1000-workflow probe of every call our code makes, but under the full suite's concurrent load its
  persistence calls hit `context deadline exceeded`, and runs stopped advancing (nodes never reached
  running; ~10 Playwright failures). Not retried here: the probe was sequential, and whether the cause
  is the memory limit or SQLite write throughput is unmeasured. Estimated saving was 150–400MiB.
- **Tuning `auto-setup` / `temporalio/server` on PostgreSQL.** More moving parts (schema job, pools,
  shard count) for an unmeasured saving.

## Not verified until a full `test -Pe2e` run

The per-phase peak reductions themselves:
the figures above are from component measurements, not a whole-suite run.
