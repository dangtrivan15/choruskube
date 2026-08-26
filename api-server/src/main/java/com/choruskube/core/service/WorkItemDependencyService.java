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

    /**
     * Agent/internal entry (mirrors {@link EpicService#create(com.choruskube.core.dto.EpicRequest,
     * UUID)}): no request-scoped TenantContext, so unlike {@link #create(CreateDependencyRequest)}
     * this guards both endpoints against the originating run's own org via {@code assertSameOrg}
     * rather than {@code checkOrgAccess} — the latter reads the caller's TenantContext, which is
     * never populated on the JOB_SECRET path and throws {@code UnresolvableTenantException} (403)
     * under a Keycloak-enabled deployment.
     */
    DependencyEdgeResponse createForRun(CreateDependencyRequest request, UUID runId);

    void delete(UUID id);

    /**
     * Removes every dependency edge referencing the given item, on either side. Used by {@link
     * DefaultStoryService#delete} and {@link DefaultTaskService#delete} so a deleted Story/Task
     * doesn't leave a dangling {@code work_item_dependency} row behind (the column has no DB-level
     * FK/{@code ON DELETE CASCADE}, since it's a polymorphic reference to either table).
     */
    void deleteAllReferencing(BlockableItemType itemType, UUID itemId);
}
