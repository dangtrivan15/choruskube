package com.choruskube.core.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * Strategy for turning a Spring Security {@link Authentication} into a
 * tenant-scoped identity tuple. Selected at startup by Spring
 * {@code @ConditionalOnProperty} keyed on {@code auth.enabled}:
 * <ul>
 *   <li>the auth-enabled JWT resolver when {@code auth.enabled=true}</li>
 *   <li>{@link SingleTenantResolver} when {@code auth.enabled} is unset or false (default)</li>
 * </ul>
 *
 * <p>Implementations must tolerate a {@code null} {@code authentication} argument:
 * the OSS / single-tenant impl ignores it entirely and stamps the system org;
 * the auth-enabled impl throws {@link com.choruskube.core.exception.UnresolvableTenantException}
 * because a null authentication on the auth-enabled path is a programming error.
 */
public interface TenantResolver {

    /** Resolved tenant tuple. Same shape as the legacy {@code TenantResolutionService.ResolvedTenant}. */
    record ResolvedTenant(UUID organizationId, UUID userId) {}

    /**
     * Resolve the tenant identity tuple from the given authentication, or stamp
     * a default in single-tenant mode.
     *
     * @param authentication current request's authentication, or null in single-tenant mode
     * @return the resolved (organizationId, userId)
     * @throws com.choruskube.core.exception.UnresolvableTenantException if a JWT impl is
     *     active but no valid org claim is present
     */
    ResolvedTenant resolve(Authentication authentication);

    /**
     * Resolve only if this authentication is one the active strategy handles. The default
     * (single-tenant) treats every authentication as applicable. The JWT strategy returns
     * {@link Optional#empty()} for non-JWT authentications (internal/WebSocket), so callers
     * such as {@code TenantFilter} can skip them without referencing oauth2 types.
     *
     * @throws com.choruskube.core.exception.UnresolvableTenantException if applicable but invalid
     */
    default Optional<ResolvedTenant> resolveIfApplicable(Authentication authentication) {
        return Optional.of(resolve(authentication));
    }
}
