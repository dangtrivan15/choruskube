package com.choruskube.core.service;

import java.util.UUID;

/**
 * OSS seam for tier-based quota enforcement.
 *
 * <p>This interface exposes only the three quota-check entry points that OSS-core callers
 * ({@code RunService}, {@code InternalRunService}, {@code GitRepoService}) depend on. The single
 * implementation is provided by a separate module (not part of OSS core), which also
 * carries the richer config/usage/K8s-quota surface not needed by core.
 *
 * <p>OSS (single-tenant) contexts have no quota bean, so core callers inject
 * {@code Optional<QuotaChecker>} and short-circuit with {@code ifPresent(...)} —
 * {@code Optional.empty()} means "no quota enforcement".
 *
 * <p>Transactional semantics live on the implementation, not here: {@code QuotaService}'s
 * {@code checkRunQuota} declares {@code @Transactional(propagation = MANDATORY)} so the per-org
 * advisory lock it acquires is held until the caller's surrounding transaction commits. Spring
 * honors that annotation on the proxied implementation regardless of this interface.
 */
public interface QuotaChecker {

    /**
     * Enforces the per-org run-start quota. Org-free at the seam: the single caller
     * ({@code RunService.startRun}) no longer resolves an org for the gate — the implementation
     * reads the active tenant from {@code TenantContext} (mirroring {@link #checkRepoQuota()}). Runs
     * in the request scope where the tenant is always established.
     */
    void checkRunQuota();

    /**
     * Enforces the per-org repo quota. Org-free at the seam: the single caller
     * ({@code GitRepoService.create}) no longer resolves an org for the gate — the implementation
     * reads the active tenant from {@code TenantContext}.
     */
    void checkRepoQuota();

    /**
     * Enforces the per-org monthly node-execution quota. Org-free at the seam: the run id is passed
     * rather than an org. The agent path that triggers node-execution creation has no
     * {@code TenantContext}, but the run and its ownership row exist by node-execution time, so the
     * implementation resolves the owning org from the run id.
     */
    void checkNodeExecutionQuota(UUID runId);
}
