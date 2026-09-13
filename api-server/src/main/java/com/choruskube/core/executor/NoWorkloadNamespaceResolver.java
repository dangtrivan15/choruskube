package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Default {@link WorkloadNamespaceResolver} for deployments with no per-org namespaces: resolves
 * every namespace to {@code ""}, which the Docker and single-tenant workers ignore.
 *
 * <p><b>Not a Spring bean.</b> Both the {@code /worker} node-execution route and
 * {@code WorkloadService.prepareWorkload} hold it as the {@code ObjectProvider} fallback, so a real
 * implementation replaces it by existing. Using one resolver in both places is what keeps the launch
 * namespace and the teardown namespace in lockstep.
 */
public class NoWorkloadNamespaceResolver implements WorkloadNamespaceResolver {

    @Override
    public String resolve(UUID runId) {
        return "";
    }
}
