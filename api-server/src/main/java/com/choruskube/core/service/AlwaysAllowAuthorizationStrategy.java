package com.choruskube.core.service;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link AuthorizationStrategy} that permits all org access. Active when
 * {@code auth.enabled} is absent or {@code false} (the default) — used in the
 * OSS / single-tenant Docker stack where there is one implicit org and one
 * implicit user. Every check is a no-op.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class AlwaysAllowAuthorizationStrategy implements AuthorizationStrategy {

    @Override
    public void checkOrgAccess(String entityType, UUID entityId) {
        // no-op
    }

    @Override
    public void checkTemplateReadAccess(boolean isSystem, UUID entityId) {
        // no-op
    }

    @Override
    public void assertSameOrg(String typeA, UUID idA, String typeB, UUID idB) {
        // no-op
    }
}
