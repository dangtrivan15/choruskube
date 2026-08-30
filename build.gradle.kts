import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import javax.inject.Inject

// ---------------------------------------------------------------------------------------
// Report root
// ---------------------------------------------------------------------------------------
// `-Dtest.reports.dir=<abs path>` is a per-repo report ROOT, not a leaf: every suite nests
// a directory under it (api-server/, orchestrator/, web-ui/, playwright/) and the timing
// report lands at the top. The repo name belongs IN the value the caller passes, because a
// multi-repo test run executes several repos' test commands in the same working directory —
// two repos that both have an `api-server` component would otherwise overwrite each other.
//
// Unset => the provider is absent => every consumer keeps the exact path it used before this
// existed, so a plain local run is unchanged. Each component build file reads the same
// system property independently rather than inheriting a value from here.
val reportsRoot: Provider<String> = providers.systemProperty("test.reports.dir")

// ---------------------------------------------------------------------------------------
// Per-phase timing report
// ---------------------------------------------------------------------------------------
// Collects wall-clock durations for every task Gradle executes and renders the phase table.
//
// Registered through BuildEventsListenerRegistry rather than `gradle.buildFinished {}` —
// that API was removed in Gradle 9, which this build is on. The report is emitted from
// close(), which Gradle invokes exactly once at the end of the build, so it prints on
// success AND on failure without needing a second hook.
//
// Timing must never be the reason a build fails: everything here is read-only bookkeeping,
// a phase that did not run renders "-", and nothing throws.
abstract class TaskTimingService :
    BuildService<TaskTimingService.Params>, OperationCompletionListener, AutoCloseable {

    interface Params : BuildServiceParameters {
        val tsvFile: RegularFileProperty
        val txtFile: RegularFileProperty
        val title: Property<String>
    }

    private data class Span(val start: Long, val end: Long) {
        val duration: Long get() = end - start
    }

    // onFinish is called from Gradle's event thread(s); guard the accumulator.
    private val spans = java.util.Collections.synchronizedMap(linkedMapOf<String, Span>())

    override fun onFinish(event: FinishEvent) {
        if (event !is TaskFinishEvent) return
        val r = event.result
        if (r.endTime < r.startTime) return
        spans[event.descriptor.taskPath] = Span(r.startTime, r.endTime)
    }

    // The unit stage, in the order the table prints it. Left column is the task path Gradle
    // reports; right column is the human label. api-server contributes no `init` row — its
    // dependency resolution happens during Gradle's configuration phase, which is not a task.
    private val unitPhases = listOf(
        ":api-server:compileJava" to "api-server    build  (javac)",
        ":api-server:compileTestJava" to "api-server    build  (javac tests)",
        ":api-server:test" to "api-server    test   (junit)",
        ":orchestrator:goModDownload" to "orchestrator  init   (go mod download)",
        ":orchestrator:compileTests" to "orchestrator  build  (test binaries)",
        ":orchestrator:test" to "orchestrator  test   (go test)",
        ":worker:goModDownload" to "worker        init   (go mod download)",
        ":worker:compileTests" to "worker        build  (test binaries)",
        ":worker:test" to "worker        test   (go test)",
        ":web-ui:npmInstall" to "web-ui        init   (npm ci)",
        ":web-ui:typecheck" to "web-ui        build  (tsc -b)",
        ":web-ui:test" to "web-ui        test   (vitest)",
    )

    private val e2ePhases = listOf(
        ":e2eImages" to "e2e: images",
        ":e2eStackUp" to "e2e: stack up + health",
        ":e2eSmoke" to "e2e: smoke",
        ":e2eSeed" to "e2e: seed",
        ":e2ePlaywright" to "e2e: playwright",
        ":e2eDown" to "e2e: teardown",
    )

    override fun close() {
        val recorded = synchronized(spans) { LinkedHashMap(spans) }
        val known = (unitPhases + e2ePhases).map { it.first }.toSet()
        // Anything not in the two maps above is only worth a line if it is actually slow;
        // otherwise a bare `./gradlew help` would print a table of noise.
        val other = recorded.filterKeys { it !in known }
            .filterValues { it.duration >= 1_000 }
            .entries.sortedByDescending { it.value.duration }
            .take(10)

        val unitRows = unitPhases.map { (path, label) -> label to recorded[path] }
        val e2eRows = e2ePhases.map { (path, label) -> label to recorded[path] }
        val unitRan = unitRows.any { it.second != null }
        val e2eRan = e2eRows.any { it.second != null }
        if (!unitRan && !e2eRan && other.isEmpty()) return

        // Wall clock, not the sum of task times: with org.gradle.parallel=true the suites
        // overlap, so summing would report more time than the build actually took.
        val all = recorded.values
        val totalWall = (all.maxOf { it.end } - all.minOf { it.start }).coerceAtLeast(1)

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=== ${parameters.title.getOrElse("timing summary")} ===")
        sb.appendLine(row("PHASE", "WALL", "SHARE"))

        if (unitRan) {
            val present = unitRows.mapNotNull { it.second }
            val unitWall = present.maxOf { it.end } - present.minOf { it.start }
            sb.appendLine(row("unit (parallel)", fmt(unitWall), share(unitWall, totalWall)))
            unitRows.forEachIndexed { i, (label, span) ->
                val branch = if (i == unitRows.lastIndex) "  └ " else "  ├ "
                sb.appendLine(row(branch + label, span?.let { fmt(it.duration) } ?: "-", ""))
            }
        }
        if (e2eRan) {
            e2eRows.forEach { (label, span) ->
                sb.appendLine(
                    row(
                        label,
                        span?.let { fmt(it.duration) } ?: "-",
                        span?.let { share(it.duration, totalWall) } ?: "",
                    )
                )
            }
        }
        if (other.isNotEmpty()) {
            sb.appendLine("other tasks over 1s")
            other.forEach { (path, span) ->
                sb.appendLine(row("  ├ $path", fmt(span.duration), ""))
            }
        }
        sb.appendLine("-".repeat(62))
        sb.appendLine(row("TOTAL (wall)", fmt(totalWall), ""))

        writeQuietly(parameters.tsvFile.orNull?.asFile) { f ->
            f.writeText(
                buildString {
                    appendLine("task\tstart_epoch_ms\tend_epoch_ms\tduration_ms")
                    recorded.entries
                        .sortedByDescending { it.value.duration }
                        .forEach { (path, s) ->
                            appendLine("$path\t${s.start}\t${s.end}\t${s.duration}")
                        }
                }
            )
            sb.appendLine("raw: ${f.absolutePath}")
        }
        writeQuietly(parameters.txtFile.orNull?.asFile) { it.writeText(sb.toString()) }
        println(sb)
    }

    private fun writeQuietly(file: java.io.File?, write: (java.io.File) -> Unit) {
        if (file == null) return
        try {
            file.parentFile?.mkdirs()
            write(file)
        } catch (e: Exception) {
            // A report that cannot be written is not a build failure.
            println("timing report: could not write ${file.absolutePath}: ${e.message}")
        }
    }

    // Widest label is "  ├ orchestrator  init   (go mod download)" at 42 chars; the column is
    // sized to fit every entry in the two maps above so nothing pushes WALL out of alignment.
    private fun row(label: String, wall: String, share: String) =
        String.format("%-44s %9s %8s", label, wall, share).trimEnd()

    private fun share(ms: Long, total: Long) = String.format("%.1f%%", 100.0 * ms / total)

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60}m${String.format("%02d", s % 60)}s" else "${s}s"
    }
}

// <reports root>/<name> when the property is set, build/<name> when it is not.
fun reportFile(name: String): Provider<RegularFile> = layout.file(
    reportsRoot.map { File("$it/$name") }
        .orElse(layout.buildDirectory.file(name).map { it.asFile })
)

val timingService =
    gradle.sharedServices.registerIfAbsent("taskTiming", TaskTimingService::class) {
        parameters.title.set("choruskube test timing summary")
        parameters.tsvFile.set(reportFile("timings.tsv"))
        parameters.txtFile.set(reportFile("timings.txt"))
    }

// Injecting BuildEventsListenerRegistry is the supported way to attach an
// OperationCompletionListener; it is not reachable from the script's own scope.
abstract class ListenerRegistrar @Inject constructor(val registry: BuildEventsListenerRegistry)

objects.newInstance(ListenerRegistrar::class).registry.onTaskCompletion(timingService)

// ---------------------------------------------------------------------------------------
// E2E chain
// ---------------------------------------------------------------------------------------
// One Exec task per script rather than one Exec running an `&&` chain. Two reasons, both
// structural: Gradle times each task separately (so the table above can attribute the run),
// and Gradle buffers each task's stdout and replays it as one labeled block — which is what
// stops a failing Go/npm/Gradle dump from being shredded into the other suites' output.

// -Pe2eNoTeardown leaves the stack running instead of tearing it down on the way out. A
// caller that needs the containers alive after the chain exits — CI dumps `docker compose
// logs` and uploads the Playwright report on failure, both of which read state that
// `e2e-down.sh --volumes` destroys — opts in and runs its own teardown afterwards. Omitting
// it (every local run, and the dogfood Test node) tears down exactly as before, so the stack
// is never left behind by accident.
val e2eNoTeardown = project.hasProperty("e2eNoTeardown")

// The agent image's scripts are plain bash on the container's PATH, so they have no Gradle
// project of their own. Their tests (agent-images/claude-code/test/test-*.sh) are
// self-contained bash — each sets up a mktemp fixture, runs the real script, and exits
// non-zero on failure. Before this task they ran from nothing: no Gradle task, no workflow
// step (build-images.yml names agent-images/** only as an image-rebuild path filter), so a
// regression in check-prs or run-all-tests was invisible to CI.
//
// A single Exec over a glob rather than one task per file: the set changes as scripts are
// added, and a per-file task list would have to be edited in lockstep with the directory.
val agentScriptTest = tasks.register<Exec>("agentScriptTest") {
    description = "Run the agent image's bash script unit tests"
    group = "verification"
    workingDir = rootDir
    commandLine(
        "bash", "-c",
        """
        set -u
        failed=0
        for t in agent-images/claude-code/test/test-*.sh; do
          echo "=== ${'$'}t ==="
          bash "${'$'}t" || failed=1
        done
        exit ${'$'}failed
        """.trimIndent()
    )
}

// The unit suites are the fast gate: a unit regression should fail in seconds rather than
// after Keycloak/Temporal/object storage have spun up. mustRunAfter (not dependsOn) keeps
// the e2e tasks individually invokable — the constraint simply disappears when the unit
// tasks are not in the graph.
val unitStage = listOf(":api-server:test", ":orchestrator:test", ":worker:test", ":web-ui:test", agentScriptTest)

val e2eDown = tasks.register<Exec>("e2eDown") {
    description = "Tear down the e2e stack and wipe its volumes"
    group = "verification"
    workingDir = rootDir
    commandLine("bash", "scripts/e2e-down.sh", "--volumes")
}

val e2eImages = tasks.register<Exec>("e2eImages") {
    description = "Build the agent (and, when a build cache registry is set, application) images"
    group = "verification"
    workingDir = rootDir
    commandLine("bash", "scripts/e2e-up.sh", "--images")
    mustRunAfter(unitStage)
}

val e2eStackUp = tasks.register<Exec>("e2eStackUp") {
    description = "Start the e2e Compose stack, wait for health, load the HTTP stubs"
    group = "verification"
    workingDir = rootDir
    commandLine("bash", "scripts/e2e-up.sh", "--stack")
    mustRunAfter(e2eImages)

    // Teardown is a finalizer, not the last link in the chain. A chain member placed after
    // :e2eSmoke would be skipped the moment an earlier phase failed, leaking containers —
    // exactly the case where teardown matters most. A Gradle finalizer runs once its
    // finalized task has completed, INCLUDING when that task failed and the build is already
    // aborting. Attaching it to :e2eStackUp therefore fires teardown in precisely the right
    // set of runs: whenever the stack was started (even if starting it failed half-way, which
    // still leaves containers behind), and never when :e2eImages failed before it.
    if (!e2eNoTeardown) {
        finalizedBy(e2eDown)
    } else {
        doLast { logger.lifecycle("-Pe2eNoTeardown: leaving the stack up; the caller must tear it down") }
    }
}

val e2eSmoke = tasks.register<Exec>("e2eSmoke") {
    description = "Fail fast if any e2e service is unreachable"
    group = "verification"
    workingDir = rootDir
    commandLine("bash", "scripts/e2e-smoke.sh")
    mustRunAfter(e2eStackUp)
}

val e2eSeed = tasks.register<Exec>("e2eSeed") {
    description = "Verify the e2e seed data the api-server provisions at boot"
    group = "verification"
    workingDir = rootDir
    commandLine("bash", "e2e/setup-test-data.sh")
    mustRunAfter(e2eSmoke)
}

val e2ePlaywright = tasks.register<Exec>("e2ePlaywright") {
    description = "Run the Playwright suite against a running stack"
    group = "verification"
    workingDir = file("web-ui")
    // Playwright runs from the host against the composed stack, so the host needs web-ui's
    // dependencies present. npm ci is declared with inputs/outputs, so this is a no-op once
    // they are current — which keeps this task usable on its own against a stack that is
    // already up, without a separate install step.
    dependsOn(":web-ui:npmInstall")
    mustRunAfter(e2eSeed)

    // Worker count: -Pworkers=4 or an E2E_WORKERS env var already present in the invoking
    // shell (Exec inherits the Gradle daemon's environment, which is how CI and the dogfood
    // Test node set it); the -P property wins when both are set. Omitting both reproduces the
    // serial run exactly — playwright.config.ts falls back to one worker.
    val workers = (project.findProperty("workers") as String?) ?: System.getenv("E2E_WORKERS")
    workers?.let { environment("E2E_WORKERS", it) }

    // playwright.config.ts's reportDir const reads this and feeds both reporters: the HTML
    // reporter's outputFolder (CI-gated — inactive on this task, since it never sets CI) and
    // the JSON reporter's outputFile (unconditional, so this dogfood run's failures are still
    // harvested despite CI never being set). Unset property => the config's own default path,
    // unchanged for either reporter.
    reportsRoot.orNull?.let { environment("PLAYWRIGHT_HTML_OUTPUT_DIR", "$it/playwright") }

    val args = mutableListOf("npx", "playwright", "test")
    workers?.let { args += "--workers=$it" }
    commandLine(args)
}

// Ordering between the phases is expressed on the tasks themselves (mustRunAfter above), so
// this aggregate only has to name them. Teardown is reached through :e2eStackUp's finalizer,
// which is why it is not listed here.
tasks.register("e2eTest") {
    description = "Run the full e2e chain: images -> stack up -> smoke -> seed -> playwright"
    group = "verification"
    dependsOn(e2eImages, e2eStackUp, e2eSmoke, e2eSeed, e2ePlaywright)
}

// ---------------------------------------------------------------------------------------
// Aggregates
// ---------------------------------------------------------------------------------------
tasks.register("test") {
    description = "Run all component test suites (add -Pe2e for the full stack chain)"
    group = "verification"
    dependsOn(unitStage)
    if (project.hasProperty("e2e")) {
        dependsOn("e2eTest")
    }
}

val checkCommentRefs = tasks.register<Exec>("checkCommentRefs") {
    group = "verification"
    description = "Fails when code cites a run-scoped spec identifier (Decision N, Caveat N, §N)"
    commandLine("./scripts/check-comment-refs.sh")
}

tasks.named("test") {
    dependsOn(checkCommentRefs)
}

tasks.register("coverageCheck") {
    description = "Enforce test coverage thresholds across all components"
    group = "verification"
    dependsOn(":api-server:coverageCheck", ":orchestrator:coverageCheck", ":worker:coverageCheck", ":web-ui:coverageCheck")
}
