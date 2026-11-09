package com.choruskube.core.credential;

import java.util.UUID;

/** Resolves a usable GitHub token for a run. */
public interface GitHubCredentialResolver {
    /** Resolve a usable GitHub token (PAT, or freshly-minted App installation token) for this run. */
    String getTokenForRun(UUID runId);
}
