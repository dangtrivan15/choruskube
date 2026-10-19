package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.service.GitHubAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvGitHubCredentialResolverTest {

    private static final String NO_APP = "";

    private final GitHubTokenScopeResolver scopeResolver = mock(GitHubTokenScopeResolver.class);

    @TempDir
    Path tempDir;

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
    void appBranch_passesTheRunsScopeToTheMint() throws IOException {
        UUID runId = UUID.randomUUID();
        GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));
        when(scopeResolver.forRun(runId)).thenReturn(scope);
        GitHubAppService gitHubAppService = mock(GitHubAppService.class);
        when(gitHubAppService.generateInstallationToken(any(), any(), any(), eq(scope)))
                .thenReturn("ghs_scoped");

        EnvGitHubCredentialResolver resolver = resolverWithApp(gitHubAppService);

        assertThat(resolver.getTokenForRun(runId)).isEqualTo("ghs_scoped");
        verify(gitHubAppService).generateInstallationToken(any(), any(), any(), eq(scope));
    }

    @Test
    void appBranch_perRepo_passesTheRepoScopeToTheMint() throws IOException {
        UUID repoId = UUID.randomUUID();
        GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api"));
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

    /** A PEM path pointing at a throwaway file — the mocked service never parses it. */
    private EnvGitHubCredentialResolver resolverWithApp(GitHubAppService gitHubAppService) throws IOException {
        Path pemFile = tempDir.resolve("test-app-key.pem");
        Files.writeString(pemFile, "not-a-real-pem");
        return new EnvGitHubCredentialResolver(
                "", "app-id", "installation-id", pemFile.toString(), gitHubAppService, scopeResolver);
    }
}
