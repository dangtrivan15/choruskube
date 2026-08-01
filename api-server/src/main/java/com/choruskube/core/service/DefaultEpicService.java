package com.choruskube.core.service;

import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalUpdateEpicRequest;
import com.choruskube.core.dto.RepoRef;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.specification.LikePatterns;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link EpicService} (Decision 8). */
@Service
public class DefaultEpicService implements EpicService {

    private static final Set<WorkItemStatus> VALID_BOARD_STAGES =
            EnumSet.of(WorkItemStatus.backlog, WorkItemStatus.in_progress, WorkItemStatus.rolled_out);

    private final EpicRepository repo;
    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final AuthorizationService authService;
    private final RunEventPublisher eventPublisher;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;
    private final WorkItemDependencyService workItemDependencyService;
    private final WorkItemDependencyRepository dependencyRepo;
    private final EpicReadinessAssembler readinessAssembler;

    public DefaultEpicService(
            EpicRepository repo,
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            SoftwareProjectRepository softwareProjectRepo,
            AuthorizationService authService,
            RunEventPublisher eventPublisher,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider,
            WorkItemDependencyService workItemDependencyService,
            WorkItemDependencyRepository dependencyRepo,
            EpicReadinessAssembler readinessAssembler) {
        this.repo = repo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
        this.workItemDependencyService = workItemDependencyService;
        this.dependencyRepo = dependencyRepo;
        this.readinessAssembler = readinessAssembler;
    }

    @Override
    @Transactional
    public EpicResponse create(EpicRequest request) {
        SoftwareProject project = loadSoftwareProject(request.softwareProjectId());
        // Caller-vs-resource guard: the target project must belong to the caller's org (TenantContext
        // present on this request path). No-op under always-allow; throws ForbiddenException (403) on
        // mismatch under auth.
        authService.checkOrgAccess("software_project", project.getId());
        Epic epic = persistEpic(request, project);
        applicationEventPublisher.publishEvent(MappableCreated.of("epic", epic.getId()));
        eventPublisher.publishRoadmapItemChanged("epic", epic.getId(), "backlog");
        auditSink.record(AuditSink.EPIC_CREATED, "epic", epic.getId(), detailJson(null, snapshot(epic)));
        return toResponse(epic, project);
    }

    @Override
    @Transactional
    public EpicResponse create(EpicRequest request, UUID runId) {
        // Agent/internal entry: no request-scoped TenantContext, so this path is unaudited.
        SoftwareProject project = loadSoftwareProject(request.softwareProjectId());
        // Cross-org guard: the target project and the originating run must belong to the same org.
        authService.assertSameOrg("software_project", project.getId(), "workflow_run", runId);
        Epic epic = persistEpic(request, project);
        applicationEventPublisher.publishEvent(
                MappableCreated.withParent("epic", epic.getId(), "software_project", project.getId()));
        eventPublisher.publishRoadmapItemChanged("epic", epic.getId(), "backlog");
        return toResponse(epic, project);
    }

    private Epic persistEpic(EpicRequest request, SoftwareProject project) {
        Epic epic = new Epic();
        epic.setTitle(request.title());
        epic.setDescription(request.description());
        epic.setMotivation(request.motivation());
        epic.setSoftwareProjectId(project.getId());
        return repo.save(epic);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EpicResponse> list(String title, Boolean readyToStart, Pageable pageable) {
        Specification<Epic> spec = scopeProvider.scope(Epic.class);
        if (title != null && !title.isBlank()) {
            String pattern = LikePatterns.containsIgnoreCase(title);
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern));
        }
        if (Boolean.TRUE.equals(readyToStart)) {
            // Decision 4: "has ready work" cannot be expressed as a database predicate (it needs
            // the authorization-aware graph walk from Decision 3), so the database can only
            // return the org-scoped/title-matching candidates, sorted — every one of them, not a
            // DB-level page — and the filtering plus the requested page/size window is applied
            // here in application code, after the rollup, so the response's pagination metadata
            // (total elements, page count) reflects the *filtered* set correctly.
            List<Epic> candidates = repo.findAll(spec, pageable.getSort());
            List<EpicResponse> readyResponses = toResponses(candidates).stream()
                    .filter(EpicResponse::readyToStart)
                    .toList();
            int total = readyResponses.size();
            int fromIndex = Math.min((int) pageable.getOffset(), total);
            int toIndex = Math.min(fromIndex + pageable.getPageSize(), total);
            return new PageImpl<>(readyResponses.subList(fromIndex, toIndex), pageable, total);
        }
        Page<Epic> page = repo.findAll(spec, pageable);
        List<EpicResponse> content = toResponses(page.getContent());
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public EpicResponse get(UUID id) {
        Epic epic = findOrThrow(id);
        authService.checkOrgAccess("epic", id);
        return toResponse(epic);
    }

    @Override
    @Transactional
    public EpicResponse update(UUID id, EpicRequest request) {
        Epic epic = findOrThrow(id);
        authService.checkOrgAccess("epic", id);
        if (hasStartedDescendantTasks(id)) {
            throw new ConflictException("Can only update an Epic while all of its Tasks are still in backlog");
        }
        Map<String, Object> beforeSnapshot = snapshot(epic);

        SoftwareProject newProject = loadSoftwareProject(request.softwareProjectId());
        // Caller-vs-resource guard: the new target project must belong to the caller's org. The
        // Epic itself was already checked via checkOrgAccess("epic", id) above, so the caller's
        // org == the Epic's org. No-op under always-allow; 403 on mismatch under auth.
        authService.checkOrgAccess("software_project", newProject.getId());
        epic.setTitle(request.title());
        epic.setDescription(request.description());
        epic.setMotivation(request.motivation());
        epic.setSoftwareProjectId(newProject.getId());
        epic = repo.save(epic);

        EpicResponse response = toResponse(epic, newProject);
        auditSink.record(AuditSink.EPIC_UPDATED, "epic", id, detailJson(beforeSnapshot, snapshot(epic)));
        eventPublisher.publishRoadmapItemChanged("epic", epic.getId(), response.status());
        return response;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Epic epic = findOrThrow(id);
        authService.checkOrgAccess("epic", id);
        if (hasStartedDescendantTasks(id)) {
            throw new ConflictException("Can only delete an Epic while all of its Tasks are still in backlog");
        }
        auditSink.record(AuditSink.EPIC_DELETED, "epic", id, detailJson(snapshot(epic), null));
        // Story/Task rows cascade-delete at the DB level (ON DELETE CASCADE on their FK to
        // Epic/Story respectively) when repo.delete(epic) below runs, but work_item_dependency has
        // no DB-level FK/ON DELETE CASCADE on its polymorphic blocking/blocked item columns, so any
        // edge referencing a descendant Story or Task must be cleaned up here first — otherwise it
        // dangles, referencing a now-deleted item id, and breaks the Roadmap Graph endpoint for
        // whichever other, unrelated Epic is on the other end of that edge. Mirrors the same cleanup
        // DefaultStoryService#delete and DefaultTaskService#delete already do for their own deletes.
        List<Story> stories = storyRepo.findByEpicIdOrderByCreatedAtDesc(id);
        for (Story story : stories) {
            workItemDependencyService.deleteAllReferencing(BlockableItemType.story, story.getId());
        }
        if (!stories.isEmpty()) {
            Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toSet());
            for (Task task : taskRepo.findByStoryIdIn(storyIds)) {
                workItemDependencyService.deleteAllReferencing(BlockableItemType.task, task.getId());
            }
        }
        repo.delete(epic);
        eventPublisher.publishRoadmapItemChanged("epic", id, "deleted");
    }

    @Override
    @Transactional(readOnly = true)
    public List<EpicResponse> listBySoftwareProjectId(UUID softwareProjectId) {
        List<Epic> epics = repo.findBySoftwareProjectIdOrderByCreatedAtDesc(softwareProjectId);
        if (epics.isEmpty()) return List.of();
        return toResponses(epics);
    }

    @Override
    @Transactional
    public EpicResponse updateInternal(
            UUID epicId, UUID runSoftwareProjectId, UUID runId, InternalUpdateEpicRequest req) {
        Epic epic = findOrThrow(epicId);
        // Cross-org guard: the Epic and the run must belong to the same org.
        authService.assertSameOrg("epic", epic.getId(), "workflow_run", runId);
        if (!epic.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException("Epic " + epicId + " does not belong to the run's software project");
        }
        if (hasStartedDescendantTasks(epicId)) {
            throw new ConflictException("Can only update an Epic while all of its Tasks are still in backlog");
        }

        // PATCH semantics: null → skip; non-null → apply.
        if (req.title() != null) {
            if (req.title().isBlank()) {
                throw new BadRequestException("title must not be blank");
            }
            epic.setTitle(req.title());
        }
        if (req.description() != null) {
            if (req.description().isBlank()) {
                throw new BadRequestException("description must not be blank");
            }
            epic.setDescription(req.description());
        }
        if (req.motivation() != null) {
            // Empty/blank motivation clears the field; any non-blank value is stored as-is.
            epic.setMotivation(req.motivation().isBlank() ? null : req.motivation());
        }

        epic = repo.save(epic);
        EpicResponse response = toResponse(epic);
        eventPublisher.publishRoadmapItemChanged("epic", epic.getId(), response.status());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public EpicResponse getInternal(UUID epicId, UUID runId, UUID runSoftwareProjectId) {
        Epic epic = findOrThrow(epicId);
        authService.assertSameOrg("epic", epic.getId(), "workflow_run", runId);
        if (!epic.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException("Epic " + epicId + " does not belong to the run's software project");
        }
        return toResponse(epic);
    }

    @Override
    @Transactional
    public EpicResponse updateStage(UUID id, WorkItemStatus stage) {
        Epic epic = findOrThrow(id);
        authService.checkOrgAccess("epic", id);
        if (!VALID_BOARD_STAGES.contains(stage)) {
            throw new BadRequestException("Invalid board stage: " + stage);
        }
        // Deliberately no hasStartedDescendantTasks(id) guard here: unlike the full PUT edit path,
        // stage moves on the roadmap board must succeed even after descendant Tasks have started.
        Map<String, Object> beforeSnapshot = snapshot(epic);
        epic.setStage(stage);
        epic = repo.save(epic);

        EpicResponse response = toResponse(epic);
        auditSink.record(AuditSink.EPIC_STAGE_UPDATED, "epic", id, detailJson(beforeSnapshot, snapshot(epic)));
        eventPublisher.publishRoadmapItemChanged("epic", epic.getId(), stage.name());
        return response;
    }

    /**
     * True if any Task under any Story of this Epic has left {@code backlog}. Mirrors the
     * old proposal rule ("can only edit/delete while in backlog") one level down the hierarchy.
     */
    private boolean hasStartedDescendantTasks(UUID epicId) {
        List<Story> stories = storyRepo.findByEpicIdOrderByCreatedAtDesc(epicId);
        if (stories.isEmpty()) return false;
        Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toSet());
        List<Task> tasks = taskRepo.findByStoryIdIn(storyIds);
        return tasks.stream().anyMatch(t -> t.getStatus() != WorkItemStatus.backlog);
    }

    /**
     * Batch-projection of a list of {@link Epic} entities into response DTOs. Avoids the N+1 that
     * a per-epic {@code toResponse} would incur by loading all referenced software_project rows
     * and all descendant Story/Task rows (for the rollup) in a fixed number of queries.
     */
    private List<EpicResponse> toResponses(List<Epic> epics) {
        Set<UUID> projectIds = epics.stream()
                .map(Epic::getSoftwareProjectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, SoftwareProject> projectsById = projectIds.isEmpty()
                ? Map.of()
                : softwareProjectRepo.findAllById(projectIds).stream()
                        .collect(Collectors.toMap(SoftwareProject::getId, p -> p));

        Map<UUID, RollupCalculator.Rollup> rollupsByEpicId = computeRollups(epics);
        Map<UUID, Boolean> readyToStartByEpicId = computeReadyToStartByEpicId(epics);

        List<EpicResponse> out = new ArrayList<>(epics.size());
        for (Epic e : epics) {
            SoftwareProject project = projectsById.get(e.getSoftwareProjectId());
            if (project == null) {
                throw new NotFoundException(
                        "SoftwareProject not found for epic " + e.getId() + ": " + e.getSoftwareProjectId());
            }
            RollupCalculator.Rollup rollup =
                    rollupsByEpicId.getOrDefault(e.getId(), new RollupCalculator.Rollup(0, 0, "backlog"));
            boolean readyToStart = readyToStartByEpicId.getOrDefault(e.getId(), false);
            out.add(buildResponse(e, project, rollup, readyToStart));
        }
        return out;
    }

    /**
     * Batched (N+1-avoiding) "ready to start" rollup for a page/list of Epics (Decision 3): an
     * Epic counts as ready to start iff any of its own Story/Task ids is both {@code backlog} (not
     * started) and resolves to {@link Readiness#READY} via the same shared walk ({@link
     * EpicReadinessAssembler}/{@code TransitiveReadinessResolver}) that already powers the
     * per-Epic Graph View and the Story/Task list readiness badges — so this rollup cannot define
     * "blocked" any differently than those do.
     *
     * <p>Fetches Stories/Tasks for the whole batch and dependency edges for the whole batch in one
     * query each — mirroring {@link #computeRollups}'s own batching — then partitions that single
     * dependency-edge fetch back out per Epic before delegating the actual walk to {@link
     * EpicReadinessAssembler#assembleFromRows}, avoiding the per-Epic dependency-edge query that
     * calling {@link EpicReadinessAssembler#assemble} once per Epic in a loop would otherwise
     * incur (the exact N+1 shape Decision 3 rejects as an alternative).
     */
    private Map<UUID, Boolean> computeReadyToStartByEpicId(List<Epic> epics) {
        Set<UUID> epicIds = epics.stream().map(Epic::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (epicIds.isEmpty()) {
            return Map.of();
        }

        List<Story> stories = storyRepo.findByEpicIdIn(epicIds);
        Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Task> tasks = storyIds.isEmpty() ? List.of() : taskRepo.findByStoryIdIn(storyIds);
        Map<UUID, List<Task>> tasksByStoryId = tasks.stream().collect(Collectors.groupingBy(Task::getStoryId));

        // Per-Epic candidate id set and status map (Story status is its own Task rollup, Task
        // status is its own — mirrors EpicReadinessAssembler#loadEpicCandidates exactly, just
        // partitioned across every Epic in the batch instead of one epicId at a time).
        Map<UUID, Set<UUID>> candidateIdsByEpicId = new HashMap<>();
        Map<UUID, Map<UUID, String>> statusByIdByEpicId = new HashMap<>();
        Set<UUID> allCandidateIds = new HashSet<>();
        for (Story story : stories) {
            UUID epicId = story.getEpicId();
            List<Task> storyTasks = tasksByStoryId.getOrDefault(story.getId(), List.of());
            Set<UUID> candidateIds = candidateIdsByEpicId.computeIfAbsent(epicId, k -> new HashSet<>());
            Map<UUID, String> statusById = statusByIdByEpicId.computeIfAbsent(epicId, k -> new HashMap<>());
            candidateIds.add(story.getId());
            statusById.put(story.getId(), RollupCalculator.compute(storyTasks).status());
            allCandidateIds.add(story.getId());
            for (Task task : storyTasks) {
                candidateIds.add(task.getId());
                statusById.put(task.getId(), task.getStatus().name());
                allCandidateIds.add(task.getId());
            }
        }

        List<WorkItemDependency> allRows = allCandidateIds.isEmpty()
                ? List.of()
                : dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(allCandidateIds, allCandidateIds);

        Map<UUID, Boolean> result = new HashMap<>();
        for (UUID epicId : epicIds) {
            Set<UUID> candidateIds = candidateIdsByEpicId.get(epicId);
            if (candidateIds == null || candidateIds.isEmpty()) {
                // No Stories/Tasks at all under this Epic: nothing to start (Caveat/behavioral
                // test in the spec).
                result.put(epicId, false);
                continue;
            }
            Map<UUID, String> statusById = statusByIdByEpicId.get(epicId);
            List<WorkItemDependency> epicRows = allRows.stream()
                    .filter(r ->
                            candidateIds.contains(r.getBlockingItemId()) || candidateIds.contains(r.getBlockedItemId()))
                    .toList();
            EpicReadinessAssembler.Assembly assembly =
                    readinessAssembler.assembleFromRows(candidateIds, statusById, epicRows, false, null);
            boolean readyToStart = candidateIds.stream()
                    .anyMatch(id -> WorkItemStatus.backlog.name().equals(statusById.get(id))
                            && assembly.readinessById().get(id) == Readiness.READY);
            result.put(epicId, readyToStart);
        }
        return result;
    }

    /** Batched (N+1-avoiding) rollup computation for a page/list of Epics — one query per level. */
    private Map<UUID, RollupCalculator.Rollup> computeRollups(List<Epic> epics) {
        Set<UUID> epicIds = epics.stream().map(Epic::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (epicIds.isEmpty()) return Map.of();

        List<Story> stories = storyRepo.findByEpicIdIn(epicIds);
        Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<Task> tasks = storyIds.isEmpty() ? List.of() : taskRepo.findByStoryIdIn(storyIds);

        Map<UUID, List<Task>> tasksByStoryId = tasks.stream().collect(Collectors.groupingBy(Task::getStoryId));
        Map<UUID, List<UUID>> storyIdsByEpicId = stories.stream()
                .collect(
                        Collectors.groupingBy(Story::getEpicId, Collectors.mapping(Story::getId, Collectors.toList())));

        Map<UUID, RollupCalculator.Rollup> result = new HashMap<>();
        for (UUID epicId : epicIds) {
            List<Task> epicTasks = new ArrayList<>();
            for (UUID storyId : storyIdsByEpicId.getOrDefault(epicId, List.of())) {
                epicTasks.addAll(tasksByStoryId.getOrDefault(storyId, List.of()));
            }
            result.put(epicId, RollupCalculator.compute(epicTasks));
        }
        return result;
    }

    private EpicResponse buildResponse(
            Epic e, SoftwareProject project, RollupCalculator.Rollup rollup, boolean readyToStart) {
        SoftwareProjectRef projectRef = toProjectRef(project);
        List<RepoRef> repos = project.resolveRepos().stream()
                .map(g -> new RepoRef(g.getId(), g.getUrl(), RepoNameUtil.deriveRepoName(g.getUrl())))
                .toList();
        return new EpicResponse(
                e.getId(),
                e.getTitle(),
                e.getDescription(),
                e.getMotivation(),
                rollup.status(),
                e.getStage().name(),
                new EpicResponse.Progress(rollup.totalTasks(), rollup.doneTasks()),
                projectRef,
                repos,
                e.getCreatedAt(),
                e.getUpdatedAt(),
                readyToStart);
    }

    private SoftwareProjectRef toProjectRef(SoftwareProject project) {
        String type = (project instanceof RepoGroup) ? "repo_group" : "git_repo";
        return new SoftwareProjectRef(project.getId(), type, project.getName());
    }

    /**
     * Loads a non-deleted target software project by id (existence/soft-delete checks only). The
     * cross-org guard is intentionally NOT here: it differs per caller — request paths compare the
     * project against the caller's org ({@code checkOrgAccess}), while the agent create path compares
     * it against the run's org ({@code assertSameOrg}). Each caller runs the appropriate guard.
     */
    private SoftwareProject loadSoftwareProject(UUID softwareProjectId) {
        if (softwareProjectId == null) {
            throw new BadRequestException("softwareProjectId is required");
        }
        SoftwareProject project = softwareProjectRepo
                .findById(softwareProjectId)
                .orElseThrow(() -> new NotFoundException("SoftwareProject not found: " + softwareProjectId));
        if (project.getDeletedAt() != null) {
            throw new NotFoundException("SoftwareProject has been deleted: " + softwareProjectId);
        }
        return project;
    }

    private Map<String, Object> snapshot(Epic e) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("title", e.getTitle());
        snap.put("description", e.getDescription());
        snap.put("motivation", e.getMotivation());
        snap.put(
                "software_project_id",
                e.getSoftwareProjectId() != null ? e.getSoftwareProjectId().toString() : null);
        snap.put("stage", e.getStage() != null ? e.getStage().name() : null);
        return snap;
    }

    private String detailJson(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }

    private Epic findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Epic not found: " + id));
    }

    private EpicResponse toResponse(Epic e) {
        SoftwareProject project = softwareProjectRepo
                .findById(e.getSoftwareProjectId())
                .orElseThrow(() -> new NotFoundException(
                        "SoftwareProject not found for epic " + e.getId() + ": " + e.getSoftwareProjectId()));
        return toResponse(e, project);
    }

    private EpicResponse toResponse(Epic e, SoftwareProject project) {
        List<Story> stories = storyRepo.findByEpicIdOrderByCreatedAtDesc(e.getId());
        Set<UUID> storyIds = stories.stream().map(Story::getId).collect(Collectors.toSet());
        List<Task> tasks = storyIds.isEmpty() ? List.of() : taskRepo.findByStoryIdIn(storyIds);
        RollupCalculator.Rollup rollup = RollupCalculator.compute(tasks);
        // Single-item path (get/create/update): reuses the same batched helper with a one-element
        // list rather than a separate implementation, so this path can't diverge from list()'s
        // definition of readyToStart (Decision 3).
        boolean readyToStart = computeReadyToStartByEpicId(List.of(e)).getOrDefault(e.getId(), false);
        return buildResponse(e, project, rollup, readyToStart);
    }
}
