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
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
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
            WorkItemDependencyService workItemDependencyService) {
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
    public Page<EpicResponse> list(String title, Pageable pageable) {
        Specification<Epic> spec = scopeProvider.scope(Epic.class);
        if (title != null && !title.isBlank()) {
            String pattern = LikePatterns.containsIgnoreCase(title);
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("title")), pattern));
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

        List<EpicResponse> out = new ArrayList<>(epics.size());
        for (Epic e : epics) {
            SoftwareProject project = projectsById.get(e.getSoftwareProjectId());
            if (project == null) {
                throw new NotFoundException(
                        "SoftwareProject not found for epic " + e.getId() + ": " + e.getSoftwareProjectId());
            }
            RollupCalculator.Rollup rollup =
                    rollupsByEpicId.getOrDefault(e.getId(), new RollupCalculator.Rollup(0, 0, "backlog"));
            out.add(buildResponse(e, project, rollup));
        }
        return out;
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

    private EpicResponse buildResponse(Epic e, SoftwareProject project, RollupCalculator.Rollup rollup) {
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
                e.getUpdatedAt());
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
        return buildResponse(e, project, rollup);
    }
}
