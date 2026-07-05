package com.choruskube.core.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin facade over {@link AuthorizationStrategy}. The public method surface is unchanged
 * from when this class was monolithic — every existing caller (services, controllers)
 * keeps working without any import or signature change. The active strategy bean is
 * selected via {@code @ConditionalOnProperty} keyed on {@code auth.enabled}:
 * the auth-enabled role-based strategy when {@code auth.enabled=true}, or
 * {@link AlwaysAllowAuthorizationStrategy} otherwise (OSS default).
 *
 * <p>{@link #isAuthEnabled()} answers a global config question, not a per-request
 * strategy decision, and is therefore read directly from the property rather than
 * routed through the strategy bean.
 */
@Service
public class AuthorizationService {

    private final AuthorizationStrategy strategy;
    private final boolean authEnabled;

    public AuthorizationService(AuthorizationStrategy strategy, @Value("${auth.enabled:false}") boolean authEnabled) {
        this.strategy = strategy;
        this.authEnabled = authEnabled;
    }

    /**
     * Checks that the resource identified by {@code entityType}/{@code entityId} belongs to the
     * current user's org. The owning org is resolved by the active strategy (from ownership data).
     * Throws ForbiddenException if mismatched. No-op when auth is disabled.
     */
    public void checkOrgAccess(String entityType, UUID entityId) {
        strategy.checkOrgAccess(entityType, entityId);
    }

    /**
     * Variant for templates that allows system templates to be read by anyone.
     */
    public void checkTemplateReadAccess(boolean isSystem, UUID entityId) {
        strategy.checkTemplateReadAccess(isSystem, entityId);
    }

    /**
     * Asserts that two resources belong to the same organization (their owning orgs, resolved from
     * ownership data, must match). Throws ForbiddenException on mismatch. No-op when auth is disabled.
     * Does not read the request tenant context, so it is safe on the agent / JOB_SECRET path.
     */
    public void assertSameOrg(String typeA, UUID idA, String typeB, UUID idB) {
        strategy.assertSameOrg(typeA, idA, typeB, idB);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }
}
