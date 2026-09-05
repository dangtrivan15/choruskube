package com.choruskube.core.executor;

import java.util.UUID;

/**
 * The default {@link WorkloadNamespaceResolver}: this deployment runs no per-org namespaces, so
 * every workload namespace resolves to {@code ""} and the executor addresses its resources without
 * a namespace (Docker) or in whatever single namespace it was configured with.
 *
 * <p><b>Not a Spring bean.</b> The {@code /worker} node-execution route holds it as the fallback
 * behind an {@code ObjectProvider}, the same arrangement as {@code NoRegistryMirrorResolver} and
 * {@code SingleFleetWorkerAuthorizer}, so an implementation replaces it by existing rather than by
 * bean-scan ordering.
 */
public class NoWorkloadNamespaceResolver implements WorkloadNamespaceResolver {

    @Override
    public String resolve(UUID runId) {
        return "";
    }
}
