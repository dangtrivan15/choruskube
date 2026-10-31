package com.choruskube.core.credential;

import com.choruskube.core.executor.WorkloadAgentCredentialResolver;
import com.choruskube.core.executor.WorkloadAgentCredentials;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Single-tenant interim resolver: attaches this server's configured Claude token to a Claude node
 * because its stack's Worker runs {@code control-plane}. Goes away once that stack holds
 * credentials on the Worker instead.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class EnvAiCredentialResolver implements WorkloadAgentCredentialResolver {

    private final String envToken;

    public EnvAiCredentialResolver(@Value("${CLAUDE_CODE_OAUTH_TOKEN:}") String envToken) {
        this.envToken = envToken;
    }

    @Override
    public WorkloadAgentCredentials resolve(UUID runId, boolean runsClaude) {
        if (runsClaude && envToken != null && !envToken.isBlank()) {
            return new WorkloadAgentCredentials(envToken, null);
        }
        return WorkloadAgentCredentials.NONE;
    }
}
