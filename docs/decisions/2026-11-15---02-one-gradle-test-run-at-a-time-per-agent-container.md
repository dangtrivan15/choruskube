# One Gradle test run at a time per agent container

**Status:** current

## Context

Agent pods built from `choruskube-dev` were OOM-killed at their memory limit right after Gradle
test runs. The pod logs show the agent starting a Gradle build, backgrounding it, and starting
another Gradle build before the first finished. Claude Code also runs subagents in the background
by default, so one subagent per repo can each run its suite at the same moment.

A busy Gradle daemon cannot take a second build, so every concurrent build starts its own daemon,
and every build that runs tests forks its own test JVM, capped at a 2560m heap. Measured with
api-server's suite, one build peaks at about 2.4 GB (daemon plus test JVM) and two overlapping
builds at about 3.9 GB, before Claude Code itself and anything else the agent is running.

Separately, `api-server/` is a standalone build with no `gradle.properties` of its own, so it ran
with Gradle's default JVM arguments. Daemons are reused only when the arguments match, so running
tests from `api-server/` and from the repo root left two daemons resident, not one.

## Decision

**The dev image installs a Gradle init script that lets one build at a time run tests.**
`agent-images/choruskube-dev/one-test-run-at-a-time.gradle` goes in `~/.gradle/init.d/`. A build
takes an OS file lock in the temp directory when its first `Test` task starts and releases it when
the build ends, pass or fail. A second build that reaches a `Test` task meanwhile prints
`Waiting for the other Gradle build in this container to finish its tests` and waits. Under the
lock, two overlapping builds peaked at about 2.8 GB with no overlap between test JVMs, and the
waiting build finished sooner than when both ran at once, because they no longer competed for CPU.

**The lock is taken at the first test task, not at build start.** `./gradlew test -Pe2e` runs
`scripts/e2e-up.sh`, which runs `./gradlew :api-server:bootJar` inside the outer build. A lock taken
at build start would make that inner build wait on its own parent forever. The inner build runs no
tests, so it never asks for the lock.

**The agent system prompt also asks for one memory-heavy job at a time.** Its Memory section asks the agent not to overlap builds, test suites or compilers, including ones it moved to the background or handed to subagents. It also explains the lock's waiting message, so the agent doesn't read a queued build as hung. The prompt covers jobs the lock can't see, like `go build` or vitest. The lock still holds when the agent ignores the prompt.

**`api-server/gradle.properties` carries the root's `org.gradle.jvmargs`.** A build started in
`api-server/` reuses the daemon a root build left. Keep the two lines identical; a mismatch only
costs a second daemon.

## Rejected

- **Per-node-type memory sizing.** Any agent node may run the tests itself, so the footprint has to
  shrink wherever tests run.
- **A lock held for the whole build.** Deadlocks the Test node, which runs a nested Gradle build
  from `e2e-up.sh`.
- **A smaller test JVM heap so two fit.** Two daemons and two test JVMs still sit near the limit,
  and a smaller heap risks the suite itself running out of memory.
- **A prompt rule alone.** Claude Code moves any command past its 2-minute timeout to the
  background, and subagents run in the background by default, so overlap happens without the
  agent choosing it. A prompt rule is not enforced.
- **Turning backgrounding off (`CLAUDE_CODE_DISABLE_BACKGROUND_TASKS=1`).** A command that hits
  its timeout is then killed, so the timeout would have to cover a full `-Pe2e` run. The
  heartbeat only fires while Claude's output advances, so a long silent command would fail the
  node. It also makes subagents run one after another.
- **A user-level `gradle.properties` in the image to unify the daemon arguments.** It would
  silently override the repository's own settings. The per-directory file keeps them where the
  build is.
