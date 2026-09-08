package com.choruskube.core.executor;

import com.choruskube.core.dto.PrepareWorkloadResponse;
import java.util.UUID;

/**
 * The default {@link WorkloadRegistryCredentialResolver}: no deployment-specific registry
 * credential is known, so {@link com.choruskube.core.service.WorkloadService} injects none.
 *
 * <p><b>Not a Spring bean.</b> {@code WorkloadService} holds it as the fallback behind an {@code
 * ObjectProvider}, the same arrangement as {@code NoRegistryMirrorResolver}, so an implementation
 * replaces it by existing rather than by bean-scan ordering.
 */
public class NoRegistryCredentialResolver implements WorkloadRegistryCredentialResolver {

    @Override
    public PrepareWorkloadResponse.RegistryCredentialsDto resolve(UUID runId) {
        return null;
    }
}
