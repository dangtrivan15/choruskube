package com.choruskube.core.service;

import java.util.UUID;

/**
 * Strategy for tenant-scoped authorization checks.
 *
 * <p>Selected at startup by Spring {@code @ConditionalOnProperty} keyed on
 * {@code auth.enabled}:
 * <ul>
 *   <li>the auth-enabled role-based strategy when {@code auth.enabled=true}</li>
 *   <li>{@link AlwaysAllowAuthorizationStrategy} when {@code auth.enabled} is unset or false (default)</li>
 * </ul>
 *
 * <p>Callers should depend on {@link AuthorizationService}, which is a thin facade
 * around this interface — it keeps the public surface stable across the ~30 existing
 * call sites.
 */
public interface AuthorizationStrategy {

    /**
     * Asserts that the resource identified by {@code entityType}/{@code entityId} belongs to
     * the current request's org. Throws {@link com.choruskube.core.exception.ForbiddenException}
     * on mismatch under the auth-enabled authorization strategy; no-op under the always-allow
     * strategy. The resource's owning org is resolved by the active strategy (from ownership
     * data) — it is no longer supplied by the caller.
     *
     * @param entityType the resource type (an ownership type name; e.g. {@code workflow_run},
     *     {@code git_repo}, {@code repo_group}, {@code graph_template})
     * @param entityId the resource id, whose owning org is resolved and compared to the request org
     */
    void checkOrgAccess(String entityType, UUID entityId);

    /**
     * Same as {@link #checkOrgAccess} except that system-owned templates (those with
     * {@code isSystem=true}) are globally readable and bypass the org check.
     */
    void checkTemplateReadAccess(boolean isSystem, UUID entityId);

    /**
     * Asserts that two resources belong to the SAME organization. Used for cross-org guards on
     * paths where there is no request org to compare against (e.g. the agent / {@code JOB_SECRET}
     * path), or where one resource's org must match another resource's org rather than the caller's.
     *
     * <p>Both resources' owning orgs are resolved by the active strategy from ownership data (the
     * same normalization as {@link #checkOrgAccess}: {@code git_repo}/{@code repo_group} →
     * {@code software_project}). Throws {@link com.choruskube.core.exception.ForbiddenException} on
     * mismatch under the auth-enabled strategy; no-op under the always-allow strategy. Unlike
     * {@link #checkOrgAccess}, this does NOT read the request-scoped tenant context, so it is safe
     * on threads without a TenantContext.
     *
     * @param typeA the first resource's ownership type name
     * @param idA   the first resource's id
     * @param typeB the second resource's ownership type name
     * @param idB   the second resource's id
     */
    void assertSameOrg(String typeA, UUID idA, String typeB, UUID idB);
}
