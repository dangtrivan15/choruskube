package com.choruskube.core.credential;

import java.util.UUID;

/** Gates a run on credential health. */
public interface CredentialPreflightChecker {
    /**
     * Throw if the run's credentials are known-unhealthy. No-op in single-tenant core.
     *
     * <p>Runs in the run's start transaction after the run is placed, so an implementation may
     * read where it executes; a throw rolls back the run and its placement.
     */
    void checkPreflight(UUID runId);
}
