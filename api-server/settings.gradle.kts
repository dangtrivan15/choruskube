rootProject.name = "api-server"

// Standalone OSS api-server: a single Gradle project (no submodules). The Spring
// Boot plugin in build.gradle builds the runnable bootJar whose main class is the
// single @SpringBootApplication, com.choruskube.ChorusKubeApplication, which scans
// com.choruskube.* — only com.choruskube.core.* exists in this OSS slice, so it
// boots in single-tenant (no-auth) mode.
