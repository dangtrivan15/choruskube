# The e2e suite fits a memory-constrained Test-node agent

## Status

current

## Context

`./gradlew test -Pe2e` is the reliability gate, and it runs on a Test-node agent whose memory
budget can be tightly constrained — the agent container runs the Gradle build (including the forked
`:api-server:test` worker) while the Docker Compose stack runs in a docker-in-docker sidecar. Two
things made that footprint larger than the budget:

- **The forked test worker had no heap bound.** The api-server suite caches a Spring
  `ApplicationContext` per `@TestPropertySource`/`@MockitoBean` combination — dozens across the
  suite — which overruns Gradle's small default worker heap. On an unconstrained host the JVM's
  ergonomic default absorbed this; in a constrained agent it does not, so the worker could OOM.
- **The compose JVMs sized their heaps off the whole sidecar cgroup.** With no per-service limit,
  each JVM's max heap was a fraction of the entire dind memory, so the stack's resident set could
  grow to fill (and thrash) the sidecar rather than staying within a per-service budget.

The suite already runs the component tests *before* the stack (`e2eStackUp.mustRunAfter(unitStage)`
in `build.gradle.kts`), so the test-worker heap peak and the stack peak do not overlap in the agent
— that ordering is relied on here and left unchanged.

## Decision

- **Bound the forked test worker heap** (`api-server/build.gradle`): `maxHeapSize` is
  `-Dtest.maxHeapSize` with a **2560m** default. That holds the LRU-capped context set with headroom
  yet fits a constrained agent; a host with room to spare can raise it via the system property.
- **Per-service `mem_limit`** on every long-running compose service (`docker-compose.e2e.yaml`), so
  each JVM sizes its heap off its own cgroup and the stack cannot grow into the whole sidecar.
  `api-server` also sets `-XX:MaxRAMPercentage=50` so its 1536m limit still yields a ~768m heap (the
  25% container default would give ~384m and risk GC churn).

Sizes are generous — comfortably above each service's observed resident set. The forked-worker heap
is validated by a full `:api-server:test` run at 2560m (no OOM); the per-service caps are exercised
by the `test -Pe2e` CI gate that runs on every change. The intent is a bound that never fires in
normal operation, not a tight squeeze.
