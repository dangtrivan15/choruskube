package com.choruskube.core.service;

import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.model.enums.BlockableItemType;
import java.util.UUID;

/**
 * Validates and manages "blocking" dependency edges between Stories/Tasks (Roadmap Graph View,
 * Part 2). Defined as an interface, with {@link DefaultWorkItemDependencyService} as its sole
 * implementation (Decision 8).
 */
public interface WorkItemDependencyService {

    DependencyEdgeResponse create(CreateDependencyRequest request);

    void delete(UUID id);

    /**
     * Removes every dependency edge referencing the given item, on either side. Used by {@link
     * DefaultStoryService#delete} and {@link DefaultTaskService#delete} so a deleted Story/Task
     * doesn't leave a dangling {@code work_item_dependency} row behind (the column has no DB-level
     * FK/{@code ON DELETE CASCADE}, since it's a polymorphic reference to either table).
     */
    void deleteAllReferencing(BlockableItemType itemType, UUID itemId);
}
