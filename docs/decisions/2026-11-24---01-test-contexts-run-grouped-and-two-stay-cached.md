# Test classes run grouped by Spring context, two contexts stay cached, and nothing ticks in a test

**Status:** current

Supersedes the 2560m test-heap default of
[2026-09-21---01-e2e-memory-fits-constrained-test-node.md](2026-09-21---01-e2e-memory-fits-constrained-test-node.md);
its per-service compose limits stand.

## Context

A test that boots the application gets a full Spring context, about 1.3 s to build. Spring's test
framework caches each one, keyed by the test's configuration, so the next class with the same
configuration reuses it. A per-class mock, property or imported configuration is a new key and a new
context. The cache is least-recently-used and holds 32 by default.

A cached context is not idle: it keeps its heap, its connection pool, its threads, and every
`@Scheduled` reconciler ticking against the shared test database. A downstream module's suite reached
42 distinct configurations, so the cache sat full for the whole run. Its test JVM held about 770 MiB
live, about 600 threads and 320 Postgres connections, and it grew its heap toward the 2560m cap. An
agent pod running one such build hit its memory limit.

Most of those 32 contexts were never used again. Classes run in discovery order, which scatters the
classes that share a context across the run, yet at no point did later classes still need more than
8 of them. A lower cap alone
would therefore rebuild evicted contexts as later classes ask for them again.

## Decision

1. **Classes run grouped by context.** `ContextGroupingClassOrderer` (test fixtures, set as the
   default in `junit-platform.properties`) orders classes by the cache's own key, the
   `MergedContextConfiguration`, so each context's classes run back to back. Classes without a
   Spring context form one group.
2. **The cache holds 2 contexts** (`spring.test.context.cache.maxSize` in the test fixtures'
   `spring.properties`). Once a group ends, nothing asks for its context again, so 2 builds the same
   contexts that 32 does. The two settings ship together in the test fixtures: a consumer that kept
   the cap but lost the order would rebuild contexts throughout its run.
3. **An evicted context frees its memory.** Closing a context did not:
   - Mockito keeps every inline mock in a static registry. A spy holds the real bean it wraps, the
     bean holds its bean factory, and so the closed context stays reachable.
     `ReleaseInlineMocksContextCustomizerFactory` clears a context's mocks from that registry when it
     closes.
   - Closed contexts were also softly reachable. The JVM keeps a soft reference for about one second
     per free MiB of heap, which at a 2560m cap outlasts the run. The test JVM runs with
     `-XX:SoftRefLRUPolicyMSPerMB=0`, which clears them at every GC.
4. **Nothing is scheduled in a test.** `@EnableScheduling` moves from `ChorusKubeApplication` to
   `SchedulingConfig`, gated by `choruskube.scheduling.enabled` (on unless set), and the test profile
   turns it off. A reconciler ticking in a cached context touched a mock that another test asserted
   was never touched; the grouped order made that race land more often. This replaces the test
   profile's per-reconciler switches for the Autopilot and the Story-spec reconciler.
   `SchedulingConfigTest` proves the unset default ticks, because a wrong default would stop every
   reconciler in production.
5. **The test heap defaults to 1g.** The live heap after these changes is a small fraction of it
   (see *Measurements*), and a heap left to grow toward a higher cap is what reached the pod limit.
6. **The test Postgres container's `max_connections=500` override goes.** Two cached contexts hold
   two connection pools.

## Measurements

The downstream suite (about 1450 tests), on a laptop, sampled while it ran:

| | contexts built | contexts still in memory | live heap | threads | Postgres connections | time |
|---|---|---|---|---|---|---|
| before | 42 | 42 | 768 MiB | 596 | 321 | 125 s |
| cap of 16, discovery order | 42 | not measured | 600 MiB | 348 | 51 | 114 s |
| grouped, cap of 2, no mock release or soft-reference flag | 38 | 35 | 579 MiB | ~100 | 10 | 106 s |
| this decision | 38 | 2–3 | 134 MiB | 120 | 31 | 86 s |

The 42 → 38 is the downstream module declaring two widely repeated mocks once, in its base test
classes. The
second and third rows also capped each pool's idle connections, which this decision drops as
redundant once two pools remain.

Each suite at both caps, on a laptop, with the test JVM held to 4 processors
(`-XX:ActiveProcessorCount=4`) as an agent's CPU limit would hold it:

| suite | heap cap | GC pause total / longest | full GCs | heap committed, max | peak RSS | time |
|---|---|---|---|---|---|---|
| this repo's | 2560m | 1.7 s / 33 ms | 0 | 682 MiB | 1167 MiB | 83 s |
| this repo's | 1g | 2.0 s / 33 ms | 0 | 647 MiB | 1053 MiB | 87 s |
| the downstream one | 2560m | 2.3 s / 42 ms | 0 | 872 MiB | 1249 MiB | 98 s |
| the downstream one | 1g | 2.4 s / 73 ms | 0 | 713 MiB | 1211 MiB | 98 s |

The heap after any GC peaked at 374 and 458 MiB. With the leaks closed, neither heap came near 2560m
on this host, so the lower cap costs no measurable GC time; what it changes is the most the heap can
claim inside a container under load.

## Rejected

- **A lower cap without grouping.** Replaying the suite's class order through an LRU cache, a cap of
  4 builds 57 contexts and a cap of 16 builds 45, against 42 uncapped.
- **Merging test configurations alone.** Merges that keep each test's meaning took the downstream
  suite from 42 to 38 contexts. What remains are mocks and properties the tests are about.
- **A smaller connection pool in the test profile.** It frees connections and Postgres memory, not
  the heap, threads and reconcilers each cached context keeps.
- **`@DirtiesContext`.** It closes a context after its class, so every class that shares a context
  pays to build it again.
- **Narrowing the one racing test's assertion.** Every other test would stay exposed to background
  ticks.

## Consequences

- A test class with a new configuration costs one context build, about 1.3 s, and no standing memory.
- The class order differs from discovery order. A test that only passed because of the classes that
  ran before it now fails; fix the test.
- No test can rely on a scheduled tick. Tests call a reconciler's method directly. That includes the
  sweep each new context's reconcilers used to run as it booted: a test that counts rows across the
  shared database first clears what other tests left (`GitRepoServiceTest` does).
- A suite whose live heap outgrows 1g fails with an `OutOfMemoryError` in the test JVM until it passes
  `-Dtest.maxHeapSize`.
