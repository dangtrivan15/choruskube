package com.choruskube.core.executor;

import com.choruskube.core.dto.PrepareWorkloadResponse;
import java.util.UUID;

/**
 * Resolves the registry pull credential to inject into a workload's launch, if this deployment
 * has one to offer.
 *
 * <p>The sibling of {@link WorkloadRegistryMirrorResolver} — replaced the same way, by an
 * implementation existing as a bean, resolved through the same {@code ObjectProvider} fallback in
 * {@link com.choruskube.core.service.WorkloadService}. Where the credential comes from (a
 * per-org registry account, a shared platform credential, none at all) is a deployment-specific
 * provisioning detail this module does not resolve itself.
 */
public interface WorkloadRegistryCredentialResolver {

    /**
     * @param runId the run whose workload is being prepared
     * @return the pull credential to inject as the workload's image-pull secret, or {@code null}
     *     when this deployment has none to offer
     */
    PrepareWorkloadResponse.RegistryCredentialsDto resolve(UUID runId);
}
