package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.service.GitHubAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvGitHubCredentialResolverTest {

    private static final String NO_APP = "";

    private final GitHubTokenScopeResolver scopeResolver = mock(GitHubTokenScopeResolver.class);

    @Test
    void returnsThePat_whenOnlyAPatIsConfigured() {
        assertThat(resolver("ghp_configured").getTokenForRun(UUID.randomUUID())).isEqualTo("ghp_configured");
    }

    @Test
    void throwsWhenNothingIsConfigured() {
        assertThatThrownBy(() -> resolver("").getTokenForRun(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No GitHub credential configured");
    }

    @Test
    void patBranch_neverTouchesTheScopeResolver() {
        UUID runId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        EnvGitHubCredentialResolver resolver = resolver("ghp_configured");

        resolver.getTokenForRun(runId);
        resolver.getTokenForRepo(repoId);

        verifyNoInteractions(scopeResolver);
    }

    @Test
    void appBranch_passesTheRunsScopeToTheMint() {
        UUID runId = UUID.randomUUID();
        GitHubTokenScope scope = GitHubTokenScope.of(java.util.List.of("acme/api"));
        when(scopeResolver.forRun(runId)).thenReturn(scope);
        GitHubAppService gitHubAppService = mock(GitHubAppService.class);
        when(gitHubAppService.generateInstallationToken(any(), any(), any(), eq(scope)))
                .thenReturn("ghs_scoped");

        EnvGitHubCredentialResolver resolver = resolverWithApp(gitHubAppService);

        assertThat(resolver.getTokenForRun(runId)).isEqualTo("ghs_scoped");
        verify(gitHubAppService).generateInstallationToken(any(), any(), any(), eq(scope));
    }

    @Test
    void appBranch_perRepo_passesTheRepoScopeToTheMint() {
        UUID repoId = UUID.randomUUID();
        GitHubTokenScope scope = GitHubTokenScope.of(java.util.List.of("acme/api"));
        when(scopeResolver.forRepo(repoId)).thenReturn(scope);
        GitHubAppService gitHubAppService = mock(GitHubAppService.class);
        when(gitHubAppService.generateInstallationToken(any(), any(), any(), eq(scope)))
                .thenReturn("ghs_scoped");

        EnvGitHubCredentialResolver resolver = resolverWithApp(gitHubAppService);

        assertThat(resolver.getTokenForRepo(repoId)).isEqualTo("ghs_scoped");
    }

    private EnvGitHubCredentialResolver resolver(String pat) {
        return new EnvGitHubCredentialResolver(
                pat,
                NO_APP,
                NO_APP,
                NO_APP,
                new GitHubAppService(new ObjectMapper(), "https://api.github.com"),
                scopeResolver);
    }

    /** A PEM path pointing at a tempfile with throwaway content — the mocked service never parses it. */
    private EnvGitHubCredentialResolver resolverWithApp(GitHubAppService gitHubAppService) {
        try {
            var pemFile = java.nio.file.Files.createTempFile("test-app-key", ".pem");
            java.nio.file.Files.writeString(pemFile, "not-a-real-pem");
            return new EnvGitHubCredentialResolver(
                    "", "app-id", "installation-id", pemFile.toString(), gitHubAppService, scopeResolver);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
