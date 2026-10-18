package com.choruskube.core.credential;

import com.choruskube.core.service.GitHubAppService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single-tenant default: a GitHub PAT from env, or an App installation token minted from env
 * config. One credential either way, narrowed per run (or per repo) on the App branch; the PAT
 * branch never computes or touches a scope.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class EnvGitHubCredentialResolver implements GitHubCredentialResolver {

    private final String pat;
    private final String appId;
    private final String installationId;
    private final String privateKeyPath;
    private final GitHubAppService gitHubAppService;
    private final GitHubTokenScopeResolver scopeResolver;

    public EnvGitHubCredentialResolver(
            @Value("${GITHUB_PAT:}") String pat,
            @Value("${github.app.id:}") String appId,
            @Value("${github.app.installation-id:}") String installationId,
            @Value("${github.app.private-key-path:}") String privateKeyPath,
            GitHubAppService gitHubAppService,
            GitHubTokenScopeResolver scopeResolver) {
        this.pat = pat;
        this.appId = appId;
        this.installationId = installationId;
        this.privateKeyPath = privateKeyPath;
        this.gitHubAppService = gitHubAppService;
        this.scopeResolver = scopeResolver;
    }

    @Override
    public String getTokenForRun(UUID runId) {
        return resolveToken(() -> scopeResolver.forRun(runId));
    }

    /** Single-tenant: one credential for everything, so the repo id only matters for the App branch's scope. */
    @Override
    public String getTokenForRepo(UUID gitRepoId) {
        return resolveToken(() -> scopeResolver.forRepo(gitRepoId));
    }

    private String resolveToken(Supplier<GitHubTokenScope> scope) {
        boolean appConfigured = !isBlank(appId) && !isBlank(installationId) && !isBlank(privateKeyPath);
        if (appConfigured) {
            String pem;
            try {
                pem = Files.readString(Path.of(privateKeyPath));
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read GitHub App private key from env config", e);
            }
            // GitHubTokenScopeException and GitHubTokenMintException propagate unwrapped — their
            // messages are already the operator-facing detail, and wrapping them here would hide
            // that behind a generic "Failed to mint..." text the gate's held reason then shows.
            return gitHubAppService.generateInstallationToken(appId, installationId, pem, scope.get());
        }
        if (!isBlank(pat)) {
            return pat;
        }
        throw new IllegalStateException("No GitHub credential configured (set GITHUB_PAT or the github.app.* env)");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
