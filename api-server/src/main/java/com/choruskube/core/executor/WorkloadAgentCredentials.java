package com.choruskube.core.executor;

import com.choruskube.core.dto.PrepareWorkloadResponse;

/**
 * The agent credentials a {@link WorkloadAgentCredentialResolver} attaches to a launch: either
 * field may be {@code null}, meaning that credential is not attached. {@link #NONE} attaches
 * nothing.
 */
public record WorkloadAgentCredentials(
        String claudeOAuthToken, PrepareWorkloadResponse.RegistryCredentialsDto registryCredentials) {

    public static final WorkloadAgentCredentials NONE = new WorkloadAgentCredentials(null, null);
}
