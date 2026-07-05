package com.choruskube.core.service;

import com.choruskube.core.config.SingleTenant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * {@link TenantResolver} that always returns the system organization and the stable
 * synthetic implicit user. Active when {@code auth.enabled} is absent or {@code false}
 * (the default) — used in the OSS / single-tenant stack to stamp the STOMP session
 * (see {@code NoAuthStompAuthStrategy}).
 *
 * <p>Fully synthetic: the org and user ids are compile-time constants
 * ({@link SingleTenant#ID} / {@link SingleTenantUserInfoProvider#SINGLE_TENANT_USER_ID}).
 * No database I/O — core carries no identity, so the implicit user is never materialized
 * as a persistent row.
 *
 * <p>The {@code authentication} argument is ignored entirely. OSS deployments do not
 * issue JWTs and have no request-scoped tenant filter; if this resolver is invoked it must
 * still produce a usable identity, so we always stamp the system tuple.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class SingleTenantResolver implements TenantResolver {

    @Override
    public ResolvedTenant resolve(Authentication authentication) {
        return new ResolvedTenant(SingleTenant.ID, SingleTenantUserInfoProvider.SINGLE_TENANT_USER_ID);
    }
}
