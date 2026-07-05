package com.choruskube.core.credential;

import java.util.UUID;

/** Gates a run on credential health. */
public interface CredentialPreflightChecker {
    /** Throw if the run's credentials are known-unhealthy. No-op in single-tenant core. */
    void checkPreflight(UUID runId);
}
