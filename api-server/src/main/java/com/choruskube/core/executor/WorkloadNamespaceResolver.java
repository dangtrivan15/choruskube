package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Resolves the Kubernetes namespace a run's workloads execute in, so the Worker can address its
 * per-execution resources by name within that namespace instead of searching cluster-wide (which
 * would need cluster-scoped RBAC, including read-all-secrets).
 *
 * <p>The sibling of {@link WorkloadRegistryMirrorResolver} — replaced the same way, by an
 * implementation existing as a bean, resolved through an {@code ObjectProvider} fallback. Whether a
 * deployment runs per-org namespaces (multi-tenant) or a single fixed one is a deployment-specific
 * detail this module does not resolve itself.
 */
public interface WorkloadNamespaceResolver {

    /**
     * @param runId the run whose workload namespace is being resolved
     * @return the namespace the run's workloads run in, or {@code ""} when this deployment runs no
     *     per-org namespaces (the executor then ignores it)
     */
    String resolve(UUID runId);
}
