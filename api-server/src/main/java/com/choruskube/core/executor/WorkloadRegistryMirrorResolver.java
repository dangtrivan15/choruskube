package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Resolves the registry-mirror endpoints to inject into a workload's launch, if this deployment
 * provisions any.
 *
 * <p>The sibling of {@link com.choruskube.core.service.WorkerAuthorizer} — replaced the same way,
 * by an implementation existing as a bean, resolved through the same {@code ObjectProvider}
 * fallback in {@link com.choruskube.core.service.WorkloadService}. Where a mirror host lives (a
 * per-org proxy, a shared mirror, none at all) is a deployment-specific provisioning detail this
 * module does not resolve itself.
 */
public interface WorkloadRegistryMirrorResolver {

    /**
     * @param runId the run whose workload is being prepared
     * @return the mirror endpoints to inject, or {@code null} when this deployment provisions none
     */
    RegistryMirror resolve(UUID runId);
}
