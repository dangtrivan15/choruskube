package com.choruskube.core.config;

import java.util.UUID;

/**
 * Synthetic single-tenant identity constants for OSS single-tenant seams.
 *
 * <p>Used by the {@code /me} provider, STOMP session stamping, and storage-prefix resolution
 * to stamp requests and resources with a stable, compile-time identity in deployments where
 * there is exactly one implicit tenant. No request scope is involved — these are plain
 * compile-time constants.
 */
public final class SingleTenant {

    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final String SLUG = "system";

    private SingleTenant() {}
}
