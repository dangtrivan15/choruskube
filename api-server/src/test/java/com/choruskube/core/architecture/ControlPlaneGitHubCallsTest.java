package com.choruskube.core.architecture;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Core has no GitHub client at all. This test runs the source-only form of the guard over this
 * repo's own production source and fails the build on any line naming the GitHub API location —
 * there is no allowlist and no exempt class, because nothing in core is meant to call GitHub. See
 * {@code docs/decisions/2026-11-13---01-the-open-source-worker-is-local-only.md}.
 */
class ControlPlaneGitHubCallsTest {

    @Test
    void apiServerNamesTheGitHubApiNowhereInProduction() {
        ControlPlaneGitHubCallGuard.requireNoGitHubApiNamed(Path.of("src/main/java"));
    }
}
