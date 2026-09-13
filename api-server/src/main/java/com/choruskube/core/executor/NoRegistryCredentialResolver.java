package com.choruskube.core.executor;

import com.choruskube.core.dto.PrepareWorkloadResponse;
import java.util.UUID;

/**
 * Default no-op {@link WorkloadRegistryCredentialResolver}: injects no pull credential.
 *
 * <p><b>Not a Spring bean.</b> {@code WorkloadService} holds it as the {@code ObjectProvider}
 * fallback, so a real implementation replaces it by existing rather than by bean-scan ordering.
 */
public class NoRegistryCredentialResolver implements WorkloadRegistryCredentialResolver {

    @Override
    public PrepareWorkloadResponse.RegistryCredentialsDto resolve(UUID runId) {
        return null;
    }
}
