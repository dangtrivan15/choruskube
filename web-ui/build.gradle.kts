tasks.register<Exec>("npmInstall") {
    workingDir = projectDir
    commandLine("npm", "ci")
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

tasks.register<Exec>("test") {
    dependsOn("npmInstall")
    workingDir = projectDir
    commandLine("npx", "vitest", "run", "--coverage")
}

tasks.register("coverageCheck") {
    description = "Enforce test coverage thresholds (via Vitest thresholds)"
    group = "verification"
    dependsOn("test")
}
