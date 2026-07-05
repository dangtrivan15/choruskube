val goVersion = file("go.mod").readLines()
    .first { it.startsWith("go ") }
    .substringAfter("go ")
    .trim()

tasks.register<Exec>("test") {
    workingDir = projectDir
    environment("GOTOOLCHAIN", "go$goVersion")
    // Use bash to ensure build/ directory exists before go test writes coverage.out.
    // doFirst { file("build").mkdirs() } is unreliable in parallel Gradle builds
    // because the Exec task's process may start before the doFirst action completes.
    commandLine("bash", "-c", "mkdir -p build && go test -coverprofile=build/coverage.out ./...")
    finalizedBy("coverageReport")
}

tasks.register<Exec>("coverageReport") {
    workingDir = projectDir
    environment("GOTOOLCHAIN", "go$goVersion")
    commandLine("go", "tool", "cover", "-html=build/coverage.out", "-o", "build/coverage.html")
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
