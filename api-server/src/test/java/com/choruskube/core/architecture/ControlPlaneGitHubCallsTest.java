package com.choruskube.core.architecture;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.service.GitHubAppService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Enforces the control-plane GitHub call allowlist over this repo's own production classes. See
 * {@code docs/decisions/2026-10-13---02-data-plane-credential-rule.md} for the rule this guards,
 * and {@code docs/decisions/2026-10-24---01-control-plane-github-calls-are-allowlisted.md} for how
 * the guard works.
 */
class ControlPlaneGitHubCallsTest {

    private static final ControlPlaneGitHubCallGuard GUARD = ControlPlaneGitHubCallGuard.scanning(
                    GitHubAppService.class, "com.choruskube")
            .guarding(GitHubAppService.class)
            .sourceRoot(Path.of("src/main/java"));

    private static final Map<String, String> ALLOWED = Map.ofEntries(Map.entry(
            "core.credential.EnvGitHubCredentialResolver#resolveToken -> GitHubAppService#generateInstallationToken",
            "Token mint for a Worker that holds no credentials; goes when the open-source stack keeps its"
                    + " credentials on the Worker. docs/decisions/2026-10-13---02-data-plane-credential-rule.md"));

    @Test
    void apiServerCallsGitHubOnlyFromTheAllowlist() {
        GUARD.check(ALLOWED);
    }

    static Stream<String> allowedKeys() {
        return ALLOWED.keySet().stream();
    }

    @ParameterizedTest
    @MethodSource("allowedKeys")
    void droppingAnyEntry_failsNamingIt(String key) {
        Map<String, String> withoutKey = new LinkedHashMap<>(ALLOWED);
        withoutKey.remove(key);

        assertThatThrownBy(() -> GUARD.check(withoutKey))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(key)
                .hasMessageContaining(ControlPlaneGitHubCallGuard.RULE);
    }
}
