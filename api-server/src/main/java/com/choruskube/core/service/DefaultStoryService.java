package com.choruskube.core.service;

import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link StoryService} (Decision 8). */
@Service
public class DefaultStoryService implements StoryService {

    private final StoryRepository repo;
    private final EpicRepository epicRepo;
    private final TaskRepository taskRepo;
    private final AuthorizationService authService;
    private final RunEventPublisher eventPublisher;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final WorkItemDependencyService workItemDependencyService;

    public DefaultStoryService(
            StoryRepository repo,
            EpicRepository epicRepo,
            TaskRepository taskRepo,
            AuthorizationService authService,
            RunEventPublisher eventPublisher,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher,
            WorkItemDependencyService workItemDependencyService) {
        this.repo = repo;
        this.epicRepo = epicRepo;
        this.taskRepo = taskRepo;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.workItemDependencyService = workItemDependencyService;
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
        return repo.save(story);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoryResponse> list(UUID epicId) {
        findEpicOrThrow(epicId);
        authService.checkOrgAccess("epic", epicId);
        List<Story> stories = repo.findByEpicIdOrderByCreatedAtDesc(epicId);
        return toResponses(stories);
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
    public StoryResponse update(UUID id, StoryRequest request) {
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
        eventPublisher.publishRoadmapItemChanged("story", story.getId(), response.status());
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

    private boolean hasStartedTasks(UUID storyId) {
        List<Task> tasks = taskRepo.findByStoryIdOrderByCreatedAtDesc(storyId);
        return tasks.stream().anyMatch(t -> t.getStatus() != WorkItemStatus.backlog);
    }

    private List<StoryResponse> toResponses(List<Story> stories) {
        return stories.stream().map(this::toResponse).toList();
    }

    private StoryResponse toResponse(Story s) {
        List<Task> tasks = taskRepo.findByStoryIdOrderByCreatedAtDesc(s.getId());
        RollupCalculator.Rollup rollup = RollupCalculator.compute(tasks);
        return new StoryResponse(
                s.getId(),
                s.getEpicId(),
                s.getTitle(),
                s.getDescription(),
                rollup.status(),
                new EpicResponse.Progress(rollup.totalTasks(), rollup.doneTasks()),
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
        return snap;
    }

    private String detailJson(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }
}
