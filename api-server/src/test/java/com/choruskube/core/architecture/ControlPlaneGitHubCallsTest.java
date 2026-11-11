package com.choruskube.core.architecture;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.service.GitHubAppService;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Enforces the control-plane GitHub call allowlist over this repo's own production classes. The
 * allowlist is empty: a self-hosted server holds no credential, so it has nothing to mint or check.
 * The guard still fails on any new call into {@link GitHubAppService}, and on any production source
 * outside it that names the GitHub API. See {@code
 * docs/decisions/2026-10-13---02-data-plane-credential-rule.md} for the rule this guards, {@code
 * docs/decisions/2026-10-24---01-control-plane-github-calls-are-allowlisted.md} for how the guard
 * works, and {@code docs/decisions/2026-11-10---01-self-hosted-server-holds-no-credential.md} for why
 * the list is empty.
 */
class ControlPlaneGitHubCallsTest {

    private static final ControlPlaneGitHubCallGuard GUARD = ControlPlaneGitHubCallGuard.scanning(
                    GitHubAppService.class, "com.choruskube")
            .guarding(GitHubAppService.class)
            .sourceRoot(Path.of("src/main/java"));

    private static final Map<String, String> ALLOWED = Map.of();

    @Test
    void apiServerCallsGitHubOnlyFromTheAllowlist() {
        GUARD.check(ALLOWED);
    }

    @Test
    void apiServerHasNoControlPlaneGitHubCallSite() {
        assertThat(GUARD.callSites()).isEmpty();
    }
}
