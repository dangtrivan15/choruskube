package com.choruskube.core.executor;

import com.choruskube.core.dto.PrepareWorkloadResponse;
import java.util.UUID;

/**
 * Resolves the registry pull credential to inject into a workload's launch, if this deployment has
 * one to offer.
 *
 * <p>Where the credential comes from is a deployment-specific provisioning detail not resolved by
 * this module; the default is {@link NoRegistryCredentialResolver}.
 */
public interface WorkloadRegistryCredentialResolver {

    /**
     * @param runId the run whose workload is being prepared
     * @return the pull credential to inject as the workload's image-pull secret, or {@code null}
     *     when this deployment has none to offer
     */
    PrepareWorkloadResponse.RegistryCredentialsDto resolve(UUID runId);
}
