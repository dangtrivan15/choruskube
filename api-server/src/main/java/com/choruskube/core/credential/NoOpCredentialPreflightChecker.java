package com.choruskube.core.credential;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Single-tenant core never blocks a run on credential health. */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpCredentialPreflightChecker implements CredentialPreflightChecker {
    @Override
    public void checkPreflight(UUID runId) {
        // no-op
    }
}
