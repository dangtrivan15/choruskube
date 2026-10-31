package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Default no-op {@link WorkloadAgentCredentialResolver}: attaches no agent credentials.
 *
 * <p><b>Not a Spring bean.</b> {@code WorkloadService} holds it as the {@code ObjectProvider}
 * fallback, so a real implementation replaces it by existing rather than by bean-scan ordering.
 */
public class NoWorkloadAgentCredentialResolver implements WorkloadAgentCredentialResolver {

    @Override
    public WorkloadAgentCredentials resolve(UUID runId, boolean runsClaude) {
        return WorkloadAgentCredentials.NONE;
    }
}
