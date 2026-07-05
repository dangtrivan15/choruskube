package com.choruskube.core.config;

import com.choruskube.core.service.TenantResolver;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * {@link StompAuthStrategy} for OSS / single-tenant mode. Ignores the
 * Authorization header entirely; the active {@link TenantResolver} (typically
 * {@code SingleTenantResolver} in OSS mode) supplies the system org and user ids
 * that get stamped onto the session.
 *
 * <p>Active when {@code auth.enabled} is absent or {@code false} (the default).
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoAuthStompAuthStrategy implements StompAuthStrategy {

    private final TenantResolver tenantResolver;

    public NoAuthStompAuthStrategy(TenantResolver tenantResolver) {
        this.tenantResolver = tenantResolver;
    }

    @Override
    public void authenticate(StompHeaderAccessor accessor) {
        TenantResolver.ResolvedTenant tenant = tenantResolver.resolve(null);
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs != null) {
            sessionAttrs.put(StompAuthInterceptor.SESSION_ATTR_ORG_ID, tenant.organizationId());
            sessionAttrs.put(StompAuthInterceptor.SESSION_ATTR_USER_ID, tenant.userId());
        }
    }
}
