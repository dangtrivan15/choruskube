package com.choruskube.core.credential;

import com.choruskube.core.service.GitHubAppService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Single-tenant default: a GitHub PAT from env, or an App installation token minted from env config. */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class EnvGitHubCredentialResolver implements GitHubCredentialResolver {

    private final String pat;
    private final String appId;
    private final String installationId;
    private final String privateKeyPath;
    private final GitHubAppService gitHubAppService;

    public EnvGitHubCredentialResolver(
            @Value("${GITHUB_PAT:}") String pat,
            @Value("${github.app.id:}") String appId,
            @Value("${github.app.installation-id:}") String installationId,
            @Value("${github.app.private-key-path:}") String privateKeyPath,
            GitHubAppService gitHubAppService) {
        this.pat = pat;
        this.appId = appId;
        this.installationId = installationId;
        this.privateKeyPath = privateKeyPath;
        this.gitHubAppService = gitHubAppService;
    }

    @Override
    public String getTokenForRun(UUID runId) {
        boolean appConfigured = !isBlank(appId) && !isBlank(installationId) && !isBlank(privateKeyPath);
        if (appConfigured) {
            try {
                String pem = Files.readString(Path.of(privateKeyPath));
                return gitHubAppService.generateInstallationToken(appId, installationId, pem);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to mint GitHub App installation token from env", e);
            }
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
