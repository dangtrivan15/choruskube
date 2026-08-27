package com.choruskube.core.service;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin facade over {@link AuthorizationStrategy}. The active strategy bean is
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

    public void checkOrgAccess(String entityType, UUID entityId) {
        strategy.checkOrgAccess(entityType, entityId);
    }

    public void checkTemplateReadAccess(boolean isSystem, UUID entityId) {
        strategy.checkTemplateReadAccess(isSystem, entityId);
    }

    public void assertSameOrg(String typeA, UUID idA, String typeB, UUID idB) {
        strategy.assertSameOrg(typeA, idA, typeB, idB);
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }
}
