package com.choruskube.core.executor;

import java.util.UUID;

/**
 * The default {@link WorkloadNamespaceResolver}: this deployment runs no per-org namespaces, so
 * every workload namespace resolves to {@code ""}. The Docker executor ignores it. A self-hosted
 * single-tenant Kubernetes deployment sets {@code K8S_NAMESPACE} on the worker and needs no
 * {@link WorkloadNamespaceResolver} bean; this default just returns {@code ""} for the api-server
 * GET's namespace field, which the single-tenant OSS worker ignores.
 *
 * <p><b>Not a Spring bean.</b> Both the {@code /worker} node-execution route and
 * {@code WorkloadService.prepareWorkload} hold it as the fallback behind an {@code ObjectProvider},
 * the same arrangement as {@code NoRegistryMirrorResolver} and {@code SingleFleetWorkerAuthorizer},
 * so an implementation replaces it by existing rather than by bean-scan ordering. Using it in both
 * places is what keeps the launch namespace and the teardown namespace in lockstep.
 */
public class NoWorkloadNamespaceResolver implements WorkloadNamespaceResolver {

    @Override
    public String resolve(UUID runId) {
        return "";
    }
}
