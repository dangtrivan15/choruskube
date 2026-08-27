package com.choruskube.core.service;

import com.choruskube.core.dto.BlockingChainResponse;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link BlockingChainService}. */
@Service
public class DefaultBlockingChainService implements BlockingChainService {

    /** Total nodes admitted into the returned tree before the walk is reported truncated
     * (no telemetry yet on real chain sizes; a human should sanity-check this default
     * once real usage is observed). */
    private static final int MAX_CHAIN_NODES = 200;

    /** Hops from the root before the walk is reported truncated (same caveat as
     * {@link #MAX_CHAIN_NODES}: an unvalidated-in-production default). */
    private static final int MAX_CHAIN_DEPTH = 25;

    private final StoryService storyService;
    private final TaskService taskService;
    private final WorkItemDependencyRepository dependencyRepo;

    public DefaultBlockingChainService(
            StoryService storyService, TaskService taskService, WorkItemDependencyRepository dependencyRepo) {
        this.storyService = storyService;
        this.taskService = taskService;
        this.dependencyRepo = dependencyRepo;
    }

    @Override
    @Transactional(readOnly = true)
    public BlockingChainResponse getChain(BlockableItemType itemType, UUID itemId) {
        // Root resolution goes through storyService/taskService (never a repository directly) so
        // org-scoping (checkOrgAccess) is never bypassed — a missing/foreign root item must
        // 404/403 exactly like every other org-scoped read in this codebase, so both exceptions are
        // allowed to propagate uncaught here.
        Map<UUID, String> titleById = new HashMap<>();
        Map<UUID, String> statusById = new HashMap<>();
        Map<UUID, BlockableItemType> typeById = new HashMap<>();

        if (itemType == BlockableItemType.story) {
            StoryResponse root = storyService.get(itemId);
            titleById.put(itemId, root.title());
            statusById.put(itemId, RollupCalculator.effectiveStatus(root.stage(), root.progress()));
        } else {
            TaskResponse root = taskService.get(itemId);
            titleById.put(itemId, root.title());
            statusById.put(itemId, root.status());
        }
        typeById.put(itemId, itemType);

        Set<UUID> visited = new HashSet<>();
        visited.add(itemId);
        List<WorkItemDependency> accumulatedEdges = new ArrayList<>();
        int nodeCount = 0;
        boolean truncated = false;

        Set<UUID> frontier = Set.of(itemId);
        int layer = 0;
        while (!frontier.isEmpty()) {
            if (nodeCount >= MAX_CHAIN_NODES || layer >= MAX_CHAIN_DEPTH) {
                truncated = true;
                break;
            }
            layer++;

            List<WorkItemDependency> rows = dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(frontier, frontier);

            Set<UUID> nextFrontier = new HashSet<>();
            for (WorkItemDependency row : rows) {
                if (!frontier.contains(row.getBlockedItemId())) {
                    // Only the backward direction ("who blocks a frontier item") matters here.
                    continue;
                }
                UUID blockerId = row.getBlockingItemId();
                if (visited.contains(blockerId)) {
                    // Already resolved (or already skipped as dangling) — still record the edge so
                    // the resolver's tree can represent a shared ancestor/diamond, but don't
                    // re-resolve or re-queue it.
                    accumulatedEdges.add(row);
                    continue;
                }

                try {
                    if (row.getBlockingItemType() == BlockableItemType.story) {
                        StoryResponse blocker = storyService.get(blockerId);
                        titleById.put(blockerId, blocker.title());
                        statusById.put(
                                blockerId, RollupCalculator.effectiveStatus(blocker.stage(), blocker.progress()));
                    } else {
                        TaskResponse blocker = taskService.get(blockerId);
                        titleById.put(blockerId, blocker.title());
                        statusById.put(blockerId, blocker.status());
                    }
                } catch (NotFoundException e) {
                    // Dangling edge: the row survived but the referenced item didn't (no DB-level
                    // FK, see V5__work_item_dependency.sql) — skip it, mirroring InternalRunService's
                    // existing precedent. ForbiddenException is intentionally NOT caught here: it
                    // must propagate so the whole request 403s the instant the walk touches an item
                    // outside the caller's org, wherever in the walk that happens.
                    continue;
                }

                typeById.put(blockerId, row.getBlockingItemType());
                accumulatedEdges.add(row);
                visited.add(blockerId);
                nextFrontier.add(blockerId);
                nodeCount++;
            }

            if (nodeCount >= MAX_CHAIN_NODES) {
                truncated = true;
                break;
            }
            frontier = nextFrontier;
        }

        TransitiveReadinessResolver.ChainWalkResult chainResult = TransitiveReadinessResolver.blockingChainOf(
                itemId,
                typeById::get,
                accumulatedEdges,
                statusById::get,
                titleById::get,
                MAX_CHAIN_NODES,
                MAX_CHAIN_DEPTH);

        Readiness readiness = TransitiveReadinessResolver.computeReadiness(
                        Set.of(itemId), accumulatedEdges, statusById::get)
                .get(itemId);

        return new BlockingChainResponse(
                itemType.name(),
                itemId,
                titleById.get(itemId),
                statusById.get(itemId),
                readiness,
                chainResult.blockedBy(),
                truncated || chainResult.truncated());
    }
}
