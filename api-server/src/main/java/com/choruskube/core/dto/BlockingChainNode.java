package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/** One node in a blocking-chain tree (see {@link BlockingChainResponse}): an item that
 * (transitively) blocks its parent, plus its own upstream blockers. {@code itemType} is
 * "story" or "task" (mirrors BlockableItemType#name()); {@code status} is the item's raw
 * work-item status string ("backlog"/"in_progress"/"done"). */
public record BlockingChainNode(
        String itemType, UUID itemId, String title, String status, List<BlockingChainNode> blockedBy) {}
