package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Resolves the Kubernetes namespace a run's workloads execute in, so the Worker addresses its
 * per-execution resources by name rather than searching cluster-wide (which would need
 * read-all-secrets RBAC).
 *
 * <p>Whether a deployment runs per-org namespaces or a single fixed one is deployment-specific and
 * not resolved by this module; the default is {@link NoWorkloadNamespaceResolver}.
 */
public interface WorkloadNamespaceResolver {

    /**
     * @param runId the run whose workload namespace is being resolved
     * @return the namespace the run's workloads run in, or {@code ""} when this deployment runs no
     *     per-org namespaces (the executor then ignores it)
     */
    String resolve(UUID runId);
}
