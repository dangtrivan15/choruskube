package com.choruskube.core.executor;

import java.util.UUID;

/**
 * The default {@link WorkloadRegistryMirrorResolver}: no deployment-specific registry mirror is
 * known, so {@link com.choruskube.core.service.WorkloadService} injects none.
 *
 * <p><b>Not a Spring bean.</b> {@code WorkloadService} holds it as the fallback behind an {@code
 * ObjectProvider}, the same arrangement as {@code SingleFleetWorkerAuthorizer}, so an
 * implementation replaces it by existing rather than by bean-scan ordering.
 */
public class NoRegistryMirrorResolver implements WorkloadRegistryMirrorResolver {

    @Override
    public RegistryMirror resolve(UUID runId) {
        return null;
    }
}
