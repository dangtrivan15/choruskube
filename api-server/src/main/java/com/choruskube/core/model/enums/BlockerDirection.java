package com.choruskube.core.model.enums;

/**
 * The role the OUT-OF-Epic item plays relative to the in-Epic item it connects to on an {@link
 * com.choruskube.core.dto.ExternalBlockerRef}: {@code BLOCKING} if the external item blocks the in-Epic item, {@code
 * BLOCKED} if the in-Epic item blocks the external item. Derived at read time from which side
 * ({@code blockingItemId} vs {@code blockedItemId}) of the underlying {@code work_item_dependency}
 * row the in-Epic item occupies — never persisted.
 */
public enum BlockerDirection {
    BLOCKING,
    BLOCKED
}
