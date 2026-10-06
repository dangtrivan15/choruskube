package com.choruskube.core.dto;

import com.choruskube.core.model.enums.BlockerDirection;
import java.util.UUID;

/**
 * One dependency edge that has the requested Epic itself as an endpoint (not one of its
 * Stories/Tasks), seen from that Epic. {@code itemType}/{@code itemId}/{@code title} describe the
 * other endpoint; {@code epicId}/{@code epicTitle} name that endpoint's owning Epic, which is the
 * item itself when it is an Epic.
 *
 * <p>{@code direction} is the requested Epic's own role: {@code BLOCKED} when the other item
 * blocks it, {@code BLOCKING} when it blocks the other item. That is the reverse subject of {@link
 * ExternalBlockerRef#direction()}, which names the outside item's role.
 */
public record EpicDependencyResponse(
        UUID edgeId,
        BlockerDirection direction,
        String itemType,
        UUID itemId,
        String title,
        UUID epicId,
        String epicTitle) {}
