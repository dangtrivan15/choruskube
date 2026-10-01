package com.choruskube.core.credential;

import java.util.UUID;

/** Resolves a usable GitHub token for a run, or for a repo directly. */
public interface GitHubCredentialResolver {
    /** Resolve a usable GitHub token (PAT, or freshly-minted App installation token) for this run. */
    String getTokenForRun(UUID runId);

    /**
     * Resolve a usable GitHub token for this repo directly — for callers with no run in hand, e.g.
     * closing a linked issue when a Task reaches {@code done} outside any specific run's signal
     * path.
     */
    String getTokenForRepo(UUID gitRepoId);
}
