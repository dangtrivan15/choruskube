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

    private static final Map<String, String> ALLOWED = Map.ofEntries(
            Map.entry(
                    "core.credential.EnvGitHubCredentialResolver#resolveToken -> GitHubAppService#generateInstallationToken",
                    "Token mint for a Worker that holds no credentials; goes when the open-source stack keeps its"
                            + " credentials on the Worker. docs/decisions/2026-10-13---02-data-plane-credential-rule.md"),
            Map.entry(
                    "core.service.BranchCleanupService#cleanupOne -> GitHubAppService#compareCommits",
                    "Legacy branch cleanup for workflow histories recorded before cleanup moved to the Worker."
                            + " docs/decisions/2026-10-13---01-branch-cleanup-on-the-worker.md"),
            Map.entry(
                    "core.service.BranchCleanupService#cleanupOne -> GitHubAppService#deleteRef",
                    "Legacy branch cleanup for workflow histories recorded before cleanup moved to the Worker."
                            + " docs/decisions/2026-10-13---01-branch-cleanup-on-the-worker.md"),
            Map.entry(
                    "core.service.DefaultRoadmapCandidateMaterializer#fileGithubIssue -> GitHubAppService#createIssue",
                    "One-step issue filing for the synchronous gate path and histories recorded before filing moved to"
                            + " the Worker. docs/decisions/2026-10-15---01-roadmap-issues-filed-on-the-worker.md"),
            Map.entry(
                    "core.service.GateDriftCheckService#compareOne -> GitHubAppService#compareCommits",
                    "Synchronous gate path, kept for gates opened without an asynchronous finalize mode."
                            + " docs/decisions/2026-10-11---02-async-gate-finalize-on-the-worker.md"),
            Map.entry(
                    "core.service.PullRequestMergeService#mergeAll -> GitHubAppService#fetchPullRequestDetail",
                    "Synchronous gate path, kept for gates opened without an asynchronous finalize mode."
                            + " docs/decisions/2026-10-11---02-async-gate-finalize-on-the-worker.md"),
            Map.entry(
                    "core.service.PullRequestMergeService#mergeAll -> GitHubAppService#mergePullRequest",
                    "Synchronous gate path, kept for gates opened without an asynchronous finalize mode."
                            + " docs/decisions/2026-10-11---02-async-gate-finalize-on-the-worker.md"),
            Map.entry(
                    "core.service.PullRequestMergeService#reReadAsMerged -> GitHubAppService#fetchPullRequestDetail",
                    "Synchronous gate path, kept for gates opened without an asynchronous finalize mode."
                            + " docs/decisions/2026-10-11---02-async-gate-finalize-on-the-worker.md"));

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
