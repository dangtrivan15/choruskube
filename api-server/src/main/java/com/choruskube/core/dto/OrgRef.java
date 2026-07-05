package com.choruskube.core.dto;

import java.util.UUID;

/**
 * Lightweight organization reference returned on the {@code /me} endpoint (active org +
 * memberships list). Kept minimal because these rows are always accompanied by membership
 * context the caller already has; richer org data is fetched via the dedicated organization
 * endpoints.
 */
public record OrgRef(UUID id, String slug, String displayName) {}
