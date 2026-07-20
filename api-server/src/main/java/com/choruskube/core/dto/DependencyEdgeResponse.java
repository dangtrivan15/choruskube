package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

/** A "blocking" dependency edge with both endpoints inside the requested Epic's Story/Task tree. */
public record DependencyEdgeResponse(
        UUID id,
        String blockingItemType,
        UUID blockingItemId,
        String blockedItemType,
        UUID blockedItemId,
        Instant createdAt) {}
