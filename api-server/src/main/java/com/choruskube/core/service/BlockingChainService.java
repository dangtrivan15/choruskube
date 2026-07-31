package com.choruskube.core.service;

import com.choruskube.core.dto.BlockingChainResponse;
import com.choruskube.core.model.enums.BlockableItemType;
import java.util.UUID;

/** Resolves the full, pruned blocking-chain tree for one Story/Task (blocking-chain feature,
 * Decisions 1, 2, 4, 5). Defined as an interface, with {@link DefaultBlockingChainService} as
 * its sole implementation, mirroring this codebase's other services. */
public interface BlockingChainService {

    /** @throws com.choruskube.core.exception.NotFoundException if the root item does not exist
     * @throws com.choruskube.core.exception.ForbiddenException if the root item, or any item
     *     encountered anywhere in the walk, is outside the caller's org (Decision 1 / 3.2) */
    BlockingChainResponse getChain(BlockableItemType itemType, UUID itemId);
}
