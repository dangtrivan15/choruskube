package com.choruskube.core.credential;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Single-tenant default: the Claude OAuth token comes straight from the environment. */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class EnvAiCredentialResolver implements AiCredentialResolver {

    private final String envToken;

    public EnvAiCredentialResolver(@Value("${CLAUDE_CODE_OAUTH_TOKEN:}") String envToken) {
        this.envToken = envToken;
    }

    @Override
    public String resolveOauthToken(UUID runId) {
        if (envToken == null || envToken.isBlank()) {
            throw new IllegalStateException(
                    "CLAUDE_CODE_OAUTH_TOKEN is not configured; cannot resolve Claude OAuth token");
        }
        return envToken;
    }
}
