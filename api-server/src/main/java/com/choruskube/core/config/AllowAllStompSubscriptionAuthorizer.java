package com.choruskube.core.config;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link StompSubscriptionAuthorizer} for OSS / single-tenant mode. There is exactly one org and all
 * data belongs to the system org, so any session that reached this point may subscribe to any
 * topic. The {@link StompSubscriptionInterceptor}'s CONNECT-time session-org guard already rejects
 * unauthenticated sessions, so this is safe.
 *
 * <p>Deliberately does NOT read {@code TenantContext} or any entity's org — single-tenant allows
 * all.
 *
 * <p>Active when {@code auth.enabled} is absent or {@code false} (the default).
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class AllowAllStompSubscriptionAuthorizer implements StompSubscriptionAuthorizer {

    @Override
    public boolean canSubscribe(String destination, UUID sessionOrgId, UUID sessionUserId) {
        return true;
    }
}
