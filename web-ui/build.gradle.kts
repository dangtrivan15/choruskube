// `-Dtest.reports.dir` is the per-repo report ROOT; this component nests under it. Absent =>
// vitest keeps the coverage directory configured in vitest.config.ts, so a local run is
// unchanged.
val reportsRoot: Provider<String> = providers.systemProperty("test.reports.dir")

tasks.register<Exec>("npmInstall") {
    workingDir = projectDir
    commandLine("npm", "ci")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

// The type check that `npm run test` used to run inline, as its own task: a type error is
// then reported as a build-phase failure with its own timing, separate from the vitest run.
// `-b` (build mode) is load-bearing — tsconfig.json here is a solution file with only project
// references, so a bare `tsc --noEmit` would check zero files and exit 0.
tasks.register<Exec>("typecheck") {
    description = "Type-check the web-ui project references"
    group = "build"
    dependsOn("npmInstall")
    workingDir = projectDir
    commandLine("npx", "tsc", "-b", "--noEmit")
}

tasks.register<Exec>("test") {
    dependsOn("npmInstall", "typecheck")
    workingDir = projectDir
    val args = mutableListOf("npx", "vitest", "run", "--coverage")
    reportsRoot.orNull?.let { args += "--coverage.reportsDirectory=$it/web-ui" }
    commandLine(args)
}

tasks.register("coverageCheck") {
    description = "Enforce test coverage thresholds (via Vitest thresholds)"
    group = "verification"
    dependsOn("test")
}
