package com.choruskube.core.repository;

import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkItemDependencyRepository extends JpaRepository<WorkItemDependency, UUID> {

    /** Exact-match duplicate-edge check, mirroring the {@code work_item_dependency_unique} constraint. */
    Optional<WorkItemDependency> findByBlockingItemTypeAndBlockingItemIdAndBlockedItemTypeAndBlockedItemId(
            BlockableItemType blockingItemType,
            UUID blockingItemId,
            BlockableItemType blockedItemType,
            UUID blockedItemId);

    /**
     * All dependency rows touching any of the given item ids, on either side of the edge. Used
     * both for Epic graph assembly (ids = every Story/Task id under the Epic, passed as both
     * arguments) and for cascade cleanup when a single Story/Task is deleted (ids = a singleton
     * collection containing just that item's id, passed as both arguments). Matches by id alone —
     * item ids are globally-unique UUIDs, so a cross-type collision is not a practical concern —
     * mirroring the existing {@code *IdIn} batch-finder convention used elsewhere (e.g. {@link
     * TaskRepository#findByStoryIdIn}) to avoid N+1 queries.
     */
    List<WorkItemDependency> findByBlockingItemIdInOrBlockedItemIdIn(
            Collection<UUID> blockingItemIds, Collection<UUID> blockedItemIds);
}
