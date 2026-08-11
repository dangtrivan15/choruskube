rootProject.name = "choruskube"

// The root build aggregates the three component builds so `./gradlew test` at the repo
// root is the single entrypoint for the full regression (see build.gradle.kts).
include("api-server")
include("orchestrator")
include("web-ui")

// api-server/settings.gradle.kts is deliberately kept alongside this file. Gradle ignores
// a settings file that sits inside an included subproject, so it has no effect here — it
// exists only so `cd api-server && ./gradlew …` still works as a standalone build (the
// OSS api-server slice is consumed that way by the sibling closed repo's composite build).
// Two settings files, one of which is inert in the root build, is the same arrangement the
// sibling repo already runs in production.
