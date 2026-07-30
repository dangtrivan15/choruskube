package com.choruskube.core.exception;

import java.util.UUID;

/**
 * Thrown when creating a dependency edge would close a cycle in the blocking graph (multi-step
 * blocking chain feature, Decision 5). Extends {@link ConflictException} — already mapped to HTTP
 * 409 by {@code GlobalExceptionHandler}'s generic {@code ConflictException} handler, the same base
 * class {@link ActiveRunsConflictException} uses for its own 409 case — so no new {@code
 * GlobalExceptionHandler} wiring is needed; the message names the blocking/blocked item pair so
 * the response body is distinguishable from other conflicts (e.g. a duplicate-edge 400, or another
 * {@code ConflictException} subclass's 409).
 */
public class DependencyCycleException extends ConflictException {

    public DependencyCycleException(UUID blockingItemId, UUID blockedItemId) {
        super("Creating '" + blockingItemId + "' blocks '" + blockedItemId
                + "' would close a cycle in the dependency graph");
    }
}
