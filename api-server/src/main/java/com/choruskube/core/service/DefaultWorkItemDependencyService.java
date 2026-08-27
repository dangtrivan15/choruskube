package com.choruskube.core.service;

import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.DependencyCycleException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link WorkItemDependencyService}. */
@Service
public class DefaultWorkItemDependencyService implements WorkItemDependencyService {

    private final WorkItemDependencyRepository repo;
    private final EpicRepository epicRepo;
    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;
    private final AuthorizationService authService;
    private final RunEventPublisher eventPublisher;

    public DefaultWorkItemDependencyService(
            WorkItemDependencyRepository repo,
            EpicRepository epicRepo,
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            AuthorizationService authService,
            RunEventPublisher eventPublisher) {
        this.repo = repo;
        this.epicRepo = epicRepo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public DependencyEdgeResponse create(CreateDependencyRequest request) {
        // Caller-vs-resource guard, checked independently for each endpoint against the caller's
        // own request-scoped org: a caller in one org must not be able to link two items that both
        // belong to a different org. Deliberately NOT assertSameOrg — that compares the two named
        // resources only to each other and ignores the caller's own org context entirely (see its
        // Javadoc), which would let a caller outside both orgs create the edge.
        return createInternal(request, (blockingType, blockingId, blockedType, blockedId) -> {
            authService.checkOrgAccess(blockingType.name(), blockingId);
            authService.checkOrgAccess(blockedType.name(), blockedId);
        });
    }

    @Override
    @Transactional
    public DependencyEdgeResponse createForRun(CreateDependencyRequest request, UUID runId) {
        // Agent/internal entry: no request-scoped TenantContext, so this guards each endpoint
        // against the originating run's own org instead (see interface Javadoc).
        return createInternal(request, (blockingType, blockingId, blockedType, blockedId) -> {
            authService.assertSameOrg(blockingType.name(), blockingId, "workflow_run", runId);
            authService.assertSameOrg(blockedType.name(), blockedId, "workflow_run", runId);
        });
    }

    /**
     * Shared validation + insert for both {@link #create} and {@link #createForRun} — identical
     * except for which org guard applies, supplied by the caller as {@code orgGuard}. Authorization
     * runs before the cycle check below: it must gate the request regardless of what the cycle
     * check would find, not the other way around.
     */
    private DependencyEdgeResponse createInternal(CreateDependencyRequest request, OrgGuard orgGuard) {
        BlockableItemType blockingType = parseType(request.blockingItemType());
        BlockableItemType blockedType = parseType(request.blockedItemType());
        UUID blockingId = request.blockingItemId();
        UUID blockedId = request.blockedItemId();

        if (blockingType == blockedType && blockingId.equals(blockedId)) {
            throw new BadRequestException("A work item cannot block itself");
        }

        // Duplicate check up front, ahead of the DB unique constraint, so callers get a clean 400
        // instead of an unhandled DataIntegrityViolationException; the DB constraint remains the
        // final backstop against a race between this check and the insert below.
        if (repo.findByBlockingItemTypeAndBlockingItemIdAndBlockedItemTypeAndBlockedItemId(
                        blockingType, blockingId, blockedType, blockedId)
                .isPresent()) {
            throw new BadRequestException("This dependency edge already exists");
        }

        assertItemExists(blockingType, blockingId);
        assertItemExists(blockedType, blockedId);

        orgGuard.check(blockingType, blockingId, blockedType, blockedId);

        // Cycle guard: reject before insert if this edge would close a loop with
        // existing edges anywhere in the graph — a cycle isn't necessarily confined to one Epic
        // (dependencies can cross Epics), so the reachability check reads every existing edge
        // rather than a pre-scoped subset. The traversal-time guard in TransitiveReadinessResolver
        // itself remains the second line of defense (this read-then-write check has no
        // locking, so two concurrent creates could still jointly close a cycle).
        //
        // The cycle check needs containment as well as declared edges: an Epic blocking another
        // Epic can deadlock against a Story-level edge pointing the other way, and that loop is
        // invisible to a walk over declared edges alone. Loaded whole, same as repo.findAll()
        // below: a cycle is not confined to one Epic. TransitiveReadinessResolver rebuilds its
        // internal graph from these two lists on every call (each existing edge rescans parentOf
        // to expand inheritance) — O(edges * items), acceptable at roadmap scale but a candidate
        // to cache/index if this path ever sees high-volume concurrent edge creation.
        Map<UUID, UUID> parentOf = new HashMap<>();
        storyRepo.findAll().forEach(s -> parentOf.put(s.getId(), s.getEpicId()));
        taskRepo.findAll().forEach(t -> parentOf.put(t.getId(), t.getStoryId()));

        List<WorkItemDependency> existingEdges = repo.findAll();
        if (TransitiveReadinessResolver.wouldCreateCycle(blockingId, blockedId, existingEdges, parentOf)) {
            throw new DependencyCycleException(blockingId, blockedId);
        }

        WorkItemDependency edge = new WorkItemDependency();
        edge.setBlockingItemType(blockingType);
        edge.setBlockingItemId(blockingId);
        edge.setBlockedItemType(blockedType);
        edge.setBlockedItemId(blockedId);
        edge = repo.save(edge);

        DependencyEdgeResponse response = toResponse(edge);
        eventPublisher.publishDependencyChanged(response, "created");
        return response;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        WorkItemDependency edge =
                repo.findById(id).orElseThrow(() -> new NotFoundException("Dependency not found: " + id));

        // Both endpoints must be authorized before anything is deleted or published — never a bare
        // repository.deleteById with no ownership check.
        authService.checkOrgAccess(edge.getBlockingItemType().name(), edge.getBlockingItemId());
        authService.checkOrgAccess(edge.getBlockedItemType().name(), edge.getBlockedItemId());

        DependencyEdgeResponse response = toResponse(edge);
        repo.delete(edge);
        eventPublisher.publishDependencyChanged(response, "deleted");
    }

    @Override
    @Transactional
    public void deleteAllReferencing(BlockableItemType itemType, UUID itemId) {
        List<WorkItemDependency> rows = repo.findByBlockingItemIdInOrBlockedItemIdIn(List.of(itemId), List.of(itemId));
        if (!rows.isEmpty()) {
            repo.deleteAll(rows);
        }
    }

    private void assertItemExists(BlockableItemType type, UUID id) {
        boolean exists =
                switch (type) {
                    case epic -> epicRepo.existsById(id);
                    case story -> storyRepo.existsById(id);
                    case task -> taskRepo.existsById(id);
                };
        if (!exists) {
            throw new NotFoundException(type.name() + " not found: " + id);
        }
    }

    private BlockableItemType parseType(String raw) {
        try {
            return BlockableItemType.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Invalid item type: " + raw + " (must be 'epic', 'story' or 'task')");
        }
    }

    private DependencyEdgeResponse toResponse(WorkItemDependency edge) {
        return new DependencyEdgeResponse(
                edge.getId(),
                edge.getBlockingItemType().name(),
                edge.getBlockingItemId(),
                edge.getBlockedItemType().name(),
                edge.getBlockedItemId(),
                edge.getCreatedAt());
    }

    /** The one step that differs between {@link #create} and {@link #createForRun}. */
    @FunctionalInterface
    private interface OrgGuard {
        void check(BlockableItemType blockingType, UUID blockingId, BlockableItemType blockedType, UUID blockedId);
    }
}
