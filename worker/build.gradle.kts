// No GOTOOLCHAIN pin here, deliberately. These tasks used to set it to the exact version in
// go.mod, which makes Go fetch that toolchain *as a module* from the checksum database — and
// CI disables that database (GOSUMDB=off) because module traffic goes through a dependency
// proxy, so the fetch cannot be verified and fails outright:
//
//   go: download go1.25.0: golang.org/toolchain@v0.0.1-...: verifying module:
//       checksum database disabled by GOSUMDB=off
//
// The pin never surfaced before because this file was unreachable: with no root Gradle build,
// the harness invoked `go test ./...` directly and these tasks never ran. Adding the root
// aggregator made them live, and the pin failed on its first CI run.
//
// Leaving GOTOOLCHAIN at Go's default ("auto") is both correct and stricter than it looks:
// go.mod's `go` directive still enforces the minimum version, CI pins the toolchain via
// setup-go, and a developer whose Go is too old gets a download only if one is actually
// needed — never on every invocation.

// `-Dtest.reports.dir` is the per-repo report ROOT; this component nests under it. Absent =>
// reports keep their existing build/ locations, so a local run is unchanged.
val reportsRoot: Provider<String> = providers.systemProperty("test.reports.dir")

tasks.register<Exec>("goModDownload") {
    description = "Download Go module dependencies"
    group = "build"
    workingDir = projectDir
    commandLine("go", "mod", "download")
}

// `go test -run '^$'` compiles every package's test binary and matches no test name, so it
// builds everything and runs nothing. Splitting it out means the `test` task below measures
// test execution against a warm compile cache instead of compile+execute in one number —
// and a compile error surfaces as a failure of this task, not of the test run.
tasks.register<Exec>("compileTests") {
    description = "Compile every Go test binary without running any test"
    group = "build"
    dependsOn("goModDownload")
    workingDir = projectDir
    commandLine("go", "test", "-run", "^$", "./...")
}

tasks.register<Exec>("test") {
    dependsOn("goModDownload", "compileTests")
    workingDir = projectDir
    // Use bash to ensure build/ directory exists before go test writes coverage.out.
    // doFirst { file("build").mkdirs() } is unreliable in parallel Gradle builds
    // because the Exec task's process may start before the doFirst action completes.
    commandLine("bash", "-c", "mkdir -p build && go test -coverprofile=build/coverage.out ./...")
    finalizedBy("coverageReport")
}

tasks.register<Exec>("coverageReport") {
    workingDir = projectDir
    // Only the rendered HTML moves under the report root; coverage.out stays in build/
    // because coverageCheck below reads it from there.
    val html = reportsRoot.map { "$it/worker/coverage.html" }.getOrElse("build/coverage.html")
    commandLine("bash", "-c", "mkdir -p \"\$(dirname '$html')\" && go tool cover -html=build/coverage.out -o '$html'")
}

tasks.register("coverageCheck") {
    description = "Enforce test coverage thresholds"
    group = "verification"
    dependsOn("test")

    doLast {
        val coverageFile = file("build/coverage.out")
        if (!coverageFile.exists()) {
            throw GradleException("Coverage file not found: ${coverageFile.path}. Run tests first.")
        }

        var totalStatements = 0L
        var coveredStatements = 0L

        coverageFile.readLines().drop(1).forEach { line ->
            // Format: name.go:line.col,line.col numStatements count
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 3) {
                val numStatements = parts[1].toLongOrNull() ?: 0L
                val count = parts[2].toLongOrNull() ?: 0L
                totalStatements += numStatements
                if (count > 0) coveredStatements += numStatements
            }
        }

        if (totalStatements == 0L) {
            throw GradleException("No coverage data found in ${coverageFile.path}")
        }

        val coverage = coveredStatements.toDouble() / totalStatements.toDouble()
        val coveragePct = "%.1f".format(coverage * 100)
        val threshold = 0.60

        if (coverage < threshold) {
            throw GradleException(
                "Code coverage ${coveragePct}% is below the required threshold of ${"%.0f".format(threshold * 100)}%"
            )
        }
        logger.lifecycle("Go coverage: ${coveragePct}% (threshold: ${"%.0f".format(threshold * 100)}%) ✓")
    }
}
