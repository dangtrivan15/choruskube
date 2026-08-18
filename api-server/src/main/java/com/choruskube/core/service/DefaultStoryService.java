package com.choruskube.core.service;

import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.StoryUpdateRequest;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link StoryService} (Decision 8). */
@Service
public class DefaultStoryService implements StoryService {

    private static final Set<WorkItemStatus> VALID_BOARD_STAGES =
            EnumSet.of(WorkItemStatus.backlog, WorkItemStatus.in_progress, WorkItemStatus.rolled_out);

    private final StoryRepository repo;
    private final EpicRepository epicRepo;
    private final TaskRepository taskRepo;
    private final AuthorizationService authService;
    private final RunEventPublisher eventPublisher;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final WorkItemDependencyService workItemDependencyService;
    // Populates `readiness` on list() responses (Decision 1) via the same Epic-bounded assembly
    // the Roadmap Graph View uses (Decision 2/3), so the two can never disagree.
    private final EpicReadinessAssembler readinessAssembler;
    private final ScopeProvider scopeProvider;

    public DefaultStoryService(
            StoryRepository repo,
            EpicRepository epicRepo,
            TaskRepository taskRepo,
            AuthorizationService authService,
            RunEventPublisher eventPublisher,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            WorkItemDependencyService workItemDependencyService,
            EpicReadinessAssembler readinessAssembler,
            ScopeProvider scopeProvider) {
        this.repo = repo;
        this.epicRepo = epicRepo;
        this.taskRepo = taskRepo;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.workItemDependencyService = workItemDependencyService;
        this.readinessAssembler = readinessAssembler;
        this.scopeProvider = scopeProvider;
    }

    @Override
    @Transactional
    public StoryResponse create(UUID epicId, StoryRequest request) {
        Epic epic = findEpicOrThrow(epicId);
        // Caller-vs-resource guard: the parent Epic must belong to the caller's org.
        authService.checkOrgAccess("epic", epic.getId());
        Story story = persistStory(epicId, request);
        // Decision 5: Story is never top-level — org is always inherited from its parent Epic.
        applicationEventPublisher.publishEvent(MappableCreated.withParent("story", story.getId(), "epic", epicId));
        auditSink.record(AuditSink.STORY_CREATED, "story", story.getId(), detailJson(null, snapshot(story)));
        eventPublisher.publishRoadmapItemChanged("story", story.getId(), "backlog");
        return toResponse(story);
    }

    @Override
    @Transactional
    public StoryResponse create(UUID epicId, StoryRequest request, UUID runId, UUID runSoftwareProjectId) {
        Epic epic = findEpicOrThrow(epicId);
        // Cross-org guard: the parent Epic and the originating run must belong to the same org.
        authService.assertSameOrg("epic", epic.getId(), "workflow_run", runId);
        // Cross-project guard: same org isn't enough on its own, since an org can span multiple
        // SoftwareProjects (mirrors DefaultEpicService#updateInternal's equivalent check).
        if (!epic.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException("Epic " + epicId + " does not belong to the run's software project");
        }
        Story story = persistStory(epicId, request);
        applicationEventPublisher.publishEvent(MappableCreated.withParent("story", story.getId(), "epic", epicId));
        eventPublisher.publishRoadmapItemChanged("story", story.getId(), "backlog");
        return toResponse(story);
    }

    private Story persistStory(UUID epicId, StoryRequest request) {
        Story story = new Story();
        story.setEpicId(epicId);
        story.setTitle(request.title());
        story.setDescription(request.description());
        // Create-time priority: absent (null) defaults to medium, mirroring the DB column default.
        story.setPriority(request.priority() != null ? request.priority() : Priority.medium);
        return repo.save(story);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> list(UUID epicId) {
        findEpicOrThrow(epicId);
        authService.checkOrgAccess("epic", epicId);
        return listWithReadiness(epicId, ReadinessAuthMode.PUBLIC, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> listInternal(UUID epicId, UUID runId, UUID runSoftwareProjectId) {
        Epic epic = findEpicOrThrow(epicId);
        authService.assertSameOrg("epic", epic.getId(), "workflow_run", runId);
        if (!epic.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException("Epic " + epicId + " does not belong to the run's software project");
        }
        return listWithReadiness(epicId, ReadinessAuthMode.INTERNAL_RUN, runId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StoryResponse> list(WorkItemStatus stage, Priority priority, Pageable pageable) {
        Specification<Story> spec = scopeProvider.scope(Story.class);
        if (stage != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("stage"), stage));
        }
        if (priority != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("priority"), priority));
        }
        Page<Story> page = repo.findAll(spec, pageable);
        List<StoryResponse> content =
                page.getContent().stream().map(this::toResponse).toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
    }

    /**
     * Shared body for {@link #list} and {@link #listInternal}: loads this Epic's full Story/Task
     * set once (Decision 3 — the readiness walk must be bounded to the whole Epic, not just this
     * Story's own Tasks, or a blocker in a sibling Story would be missed) and populates real
     * {@code readiness} instead of the {@code null} every other read path still returns (Decision
     * 1 — only the flat list endpoints and the Roadmap Graph View compute it).
     */
    private List<StoryResponse> listWithReadiness(UUID epicId, ReadinessAuthMode mode, UUID contextId) {
        EpicReadinessAssembler.EpicCandidates candidates = readinessAssembler.loadEpicCandidates(epicId);
        EpicReadinessAssembler.Assembly assembly = readinessAssembler.assemble(
                candidates.candidateIds(), candidates.statusById(), candidates.parentOf(), mode, contextId);
        return candidates.stories().stream()
                .map(s -> toResponse(
                        s,
                        candidates.tasksByStoryId().getOrDefault(s.getId(), List.of()),
                        assembly.readinessById().get(s.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public StoryResponse get(UUID id) {
        Story story = findOrThrow(id);
        authService.checkOrgAccess("story", id);
        return toResponse(story);
    }

    @Override
    @Transactional
    public StoryResponse update(UUID id, StoryUpdateRequest request) {
        Story story = findOrThrow(id);
        authService.checkOrgAccess("story", id);
        if (hasStartedTasks(id)) {
            throw new ConflictException("Can only update a Story while all of its Tasks are still in backlog");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("title must not be blank");
        }
        Map<String, Object> beforeSnapshot = snapshot(story);
        story.setTitle(request.title());
        story.setDescription(request.description());
        story = repo.save(story);
        StoryResponse response = toResponse(story);
        auditSink.record(AuditSink.STORY_UPDATED, "story", id, detailJson(beforeSnapshot, snapshot(story)));
        eventPublisher.publishRoadmapItemChanged(
                "story", story.getId(), story.getStage().name());
        return response;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Story story = findOrThrow(id);
        authService.checkOrgAccess("story", id);
        if (hasStartedTasks(id)) {
            throw new ConflictException("Can only delete a Story while all of its Tasks are still in backlog");
        }
        auditSink.record(AuditSink.STORY_DELETED, "story", id, detailJson(snapshot(story), null));
        // work_item_dependency has no DB-level FK/ON DELETE CASCADE on this Story's id (it's a
        // polymorphic Story-or-Task reference), so any edge referencing it must be cleaned up here.
        workItemDependencyService.deleteAllReferencing(BlockableItemType.story, id);
        // The Task table's own FK to Story IS "ON DELETE CASCADE" at the DB level, so repo.delete(story)
        // below removes descendant Tasks without going through DefaultTaskService#delete's own
        // work_item_dependency cleanup. Clean up dependency edges for each descendant Task here too,
        // otherwise they'd dangle referencing a Task id that no longer exists.
        for (Task task : taskRepo.findByStoryIdOrderByCreatedAtDesc(id)) {
            workItemDependencyService.deleteAllReferencing(BlockableItemType.task, task.getId());
        }
        repo.delete(story);
        eventPublisher.publishRoadmapItemChanged("story", id, "deleted");
    }

    @Override
    @Transactional
    public StoryResponse updateStage(UUID id, WorkItemStatus stage) {
        Story story = findOrThrow(id);
        authService.checkOrgAccess("story", id);
        if (!VALID_BOARD_STAGES.contains(stage)) {
            throw new BadRequestException("Invalid board stage: " + stage);
        }
        // Deliberately no hasStartedTasks(id) guard here: unlike the full PUT edit path, stage
        // moves on the roadmap board must succeed even after descendant Tasks have started.
        Map<String, Object> beforeSnapshot = snapshot(story);
        story.setStage(stage);
        story = repo.save(story);

        StoryResponse response = toResponse(story);
        auditSink.record(AuditSink.STORY_STAGE_UPDATED, "story", id, detailJson(beforeSnapshot, snapshot(story)));
        eventPublisher.publishRoadmapItemChanged("story", story.getId(), stage.name());
        return response;
    }

    @Override
    @Transactional
    public StoryResponse updatePriority(UUID id, Priority priority) {
        Story story = findOrThrow(id);
        authService.checkOrgAccess("story", id);
        // Deliberately no hasStartedTasks(id) guard here: like updateStage, a priority change must
        // succeed even after descendant Tasks have started. Every Priority enum value is valid, so
        // there is no value-subset check (unlike board stages, which exclude `done`).
        Map<String, Object> beforeSnapshot = snapshot(story);
        story.setPriority(priority);
        story = repo.save(story);

        StoryResponse response = toResponse(story);
        // Audited like every other roadmap mutation (create/update/delete/stage): priority is a
        // planning attribute moved in isolation, so its change belongs in the audit trail too.
        auditSink.record(AuditSink.STORY_PRIORITY_UPDATED, "story", id, detailJson(beforeSnapshot, snapshot(story)));
        eventPublisher.publishRoadmapItemChanged(
                "story", story.getId(), story.getStage().name());
        return response;
    }

    @Override
    @Transactional
    public StoryResponse updateTargetDate(UUID id, LocalDate targetDate) {
        Story story = findOrThrow(id);
        authService.checkOrgAccess("story", id);
        // Deliberately no hasStartedTasks(id) guard here: like updateStage/updatePriority, a target
        // date change must succeed even after descendant Tasks have started. No value-subset check
        // either — every LocalDate (including null, which clears it) is valid.
        Map<String, Object> beforeSnapshot = snapshot(story);
        story.setTargetDate(targetDate);
        story = repo.save(story);

        StoryResponse response = toResponse(story);
        // Audited like every other roadmap mutation (create/update/delete/stage/priority): target
        // date is a planning attribute moved in isolation, so its change belongs in the audit trail.
        auditSink.record(AuditSink.STORY_TARGET_DATE_UPDATED, "story", id, detailJson(beforeSnapshot, snapshot(story)));
        eventPublisher.publishRoadmapItemChanged(
                "story", story.getId(), story.getStage().name());
        return response;
    }

    private boolean hasStartedTasks(UUID storyId) {
        List<Task> tasks = taskRepo.findByStoryIdOrderByCreatedAtDesc(storyId);
        return tasks.stream().anyMatch(t -> t.getStatus() != WorkItemStatus.backlog);
    }

    /** Single-item read paths (create/update/get) — readiness stays {@code null} here (Decision 1
     * scopes real readiness to the flat list endpoints and the Roadmap Graph View only). */
    private StoryResponse toResponse(Story s) {
        List<Task> tasks = taskRepo.findByStoryIdOrderByCreatedAtDesc(s.getId());
        return toResponse(s, tasks, null);
    }

    private StoryResponse toResponse(Story s, List<Task> tasks, Readiness readiness) {
        RollupCalculator.Rollup rollup = RollupCalculator.compute(tasks);
        return new StoryResponse(
                s.getId(),
                s.getEpicId(),
                s.getTitle(),
                s.getDescription(),
                s.getStage().name(),
                s.getPriority().name(),
                s.getTargetDate(),
                readiness,
                new EpicResponse.Progress(rollup.totalTasks(), rollup.doneTasks(), rollup.startedTasks()),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }

    private Epic findEpicOrThrow(UUID epicId) {
        return epicRepo.findById(epicId).orElseThrow(() -> new NotFoundException("Epic not found: " + epicId));
    }

    private Story findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Story not found: " + id));
    }

    private Map<String, Object> snapshot(Story s) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("epic_id", s.getEpicId() != null ? s.getEpicId().toString() : null);
        snap.put("title", s.getTitle());
        snap.put("description", s.getDescription());
        snap.put("stage", s.getStage() != null ? s.getStage().name() : null);
        snap.put("priority", s.getPriority() != null ? s.getPriority().name() : null);
        snap.put("target_date", s.getTargetDate() != null ? s.getTargetDate().toString() : null);
        return snap;
    }

    private String detailJson(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }
}
