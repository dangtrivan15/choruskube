package com.choruskube.core.service;

import java.util.UUID;

/**
 * OSS seam for tier-based quota enforcement.
 *
 * <p>This interface exposes only the quota-check entry points that OSS-core callers
 * ({@code RunService}, {@code InternalRunService}, {@code GitRepoService}) depend on. The single
 * implementation is provided by a separate module (not part of OSS core), which also
 * carries the richer config/usage/K8s-quota surface not needed by core.
 *
 * <p>OSS (single-tenant) contexts have no quota bean, so core callers inject
 * {@code Optional<QuotaChecker>} and short-circuit with {@code ifPresent(...)} —
 * {@code Optional.empty()} means "no quota enforcement".
 *
 * <p>Transactional semantics live on the implementation, not here: {@code QuotaService}'s
 * {@code checkRunQuota} and {@code checkRunQuotaExceptConcurrency} declare
 * {@code @Transactional(propagation = MANDATORY)} so the per-org advisory lock they acquire is held
 * until the caller's surrounding transaction commits. Spring honors that annotation on the proxied
 * implementation regardless of this interface.
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
     * Enforces every run-start limit {@link #checkRunQuota()} does except the concurrent-run cap.
     * Only for a start the system makes to unblock runs that already hold concurrent-run slots and
     * wait on the new run, such as a Story revision answering parked Tasks' conflicts: the cap
     * counts those waiting runs, so at the cap a checked start is refused until they time out.
     *
     * <p>Defaults to the full check, so an implementation grants no exemption until it opts in.
     */
    default void checkRunQuotaExceptConcurrency() {
        checkRunQuota();
    }

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
