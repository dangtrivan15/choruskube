package com.choruskube.core.model.enums;

/**
 * The role one endpoint of a {@code work_item_dependency} row plays, derived at read time from
 * which side ({@code blockingItemId} vs {@code blockedItemId}) it occupies — never persisted.
 *
 * <p>Whose role it is depends on the carrying record. On a {@link
 * com.choruskube.core.dto.ExternalBlockerRef} it is the OUT-OF-Epic item's: {@code BLOCKING} if
 * the external item blocks the in-Epic item. On a {@link
 * com.choruskube.core.dto.EpicDependencyResponse} it is the requested Epic's own: {@code BLOCKED}
 * if the other item blocks that Epic.
 */
public enum BlockerDirection {
    BLOCKING,
    BLOCKED
}
