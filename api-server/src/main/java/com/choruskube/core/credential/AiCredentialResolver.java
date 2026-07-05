package com.choruskube.core.credential;

import java.util.UUID;

/** Resolves the Claude OAuth token to inject for a run. */
public interface AiCredentialResolver {
    /** Resolve the Claude OAuth token to inject as CLAUDE_CODE_OAUTH_TOKEN for this run. */
    String resolveOauthToken(UUID runId);
}
