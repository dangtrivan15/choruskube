package com.choruskube.core.executor;

import java.util.UUID;

/**
 * The default {@link WorkloadNamespaceResolver}: this deployment runs no per-org namespaces, so
 * every workload namespace resolves to {@code ""}. The Docker executor ignores it. The Kubernetes
 * executor, however, cannot create resources in the empty namespace and fails loudly at launch —
 * so a self-hosted deployment running {@code EXECUTOR_TYPE=k8s} must supply its own
 * {@link WorkloadNamespaceResolver} bean naming a real namespace; this default backs Docker only.
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
