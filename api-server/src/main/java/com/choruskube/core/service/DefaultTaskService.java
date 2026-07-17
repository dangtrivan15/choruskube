package com.choruskube.core.service;

import com.choruskube.core.config.GraphIds;
import com.choruskube.core.dto.CreateRunRequest;
import com.choruskube.core.dto.RepoRef;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.RunSummary;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link TaskService} (Decision 8). */
@Service
public class DefaultTaskService implements TaskService {

    private static final Set<WorkflowRunStatus> TERMINAL_STATUSES =
            Set.of(WorkflowRunStatus.completed, WorkflowRunStatus.failed, WorkflowRunStatus.cancelled);

    private final TaskRepository repo;
    private final StoryRepository storyRepo;
    private final EpicRepository epicRepo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final GraphTemplateRepository graphTemplateRepo;
    private final WorkflowRunRepository runRepo;
    private final RunService runService;
    private final AuthorizationService authService;
    private final RunEventPublisher eventPublisher;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    public DefaultTaskService(
            TaskRepository repo,
            StoryRepository storyRepo,
            EpicRepository epicRepo,
            SoftwareProjectRepository softwareProjectRepo,
            GraphTemplateRepository graphTemplateRepo,
            WorkflowRunRepository runRepo,
            RunService runService,
            AuthorizationService authService,
            RunEventPublisher eventPublisher,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            ApplicationEventPublisher applicationEventPublisher) {
        this.repo = repo;
        this.storyRepo = storyRepo;
        this.epicRepo = epicRepo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.graphTemplateRepo = graphTemplateRepo;
        this.runRepo = runRepo;
        this.runService = runService;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    @Transactional
    public TaskResponse create(UUID storyId, TaskRequest request) {
        Story story = findStoryOrThrow(storyId);
        authService.checkOrgAccess("story", story.getId());
        Epic epic = findEpicOrThrow(story.getEpicId());
        Task task = persistTask(storyId, request, epic.getSoftwareProjectId());
        // Decision 5: Task is never top-level — org is always inherited from its parent Story.
        applicationEventPublisher.publishEvent(MappableCreated.withParent("task", task.getId(), "story", storyId));
        auditSink.record(AuditSink.TASK_CREATED, "task", task.getId(), detailJson(null, snapshot(task)));
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        return toResponse(task);
    }

    @Override
    @Transactional
    public TaskResponse create(UUID storyId, TaskRequest request, UUID runId, UUID runSoftwareProjectId) {
        Story story = findStoryOrThrow(storyId);
        authService.assertSameOrg("story", story.getId(), "workflow_run", runId);
        Epic epic = findEpicOrThrow(story.getEpicId());
        // Cross-project guard: same org isn't enough on its own, since an org can span multiple
        // SoftwareProjects (mirrors DefaultEpicService#updateInternal's equivalent check).
        if (!epic.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException("Story " + storyId + " does not belong to the run's software project");
        }
        Task task = persistTask(storyId, request, epic.getSoftwareProjectId());
        applicationEventPublisher.publishEvent(MappableCreated.withParent("task", task.getId(), "story", storyId));
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        return toResponse(task);
    }

    private Task persistTask(UUID storyId, TaskRequest request, UUID softwareProjectId) {
        Task task = new Task();
        task.setStoryId(storyId);
        task.setTitle(request.title());
        task.setDescription(request.description());
        // Denormalized once at creation from the ancestor Epic (Decision 4) — immutable afterwards.
        task.setSoftwareProjectId(softwareProjectId);
        return repo.save(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> list(UUID storyId) {
        findStoryOrThrow(storyId);
        authService.checkOrgAccess("story", storyId);
        return repo.findByStoryIdOrderByCreatedAtDesc(storyId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponse get(UUID id) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        return toResponse(task);
    }

    @Override
    @Transactional
    public TaskResponse update(UUID id, TaskRequest request) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        if (task.getStatus() != WorkItemStatus.backlog) {
            throw new ConflictException("Can only update tasks in backlog status");
        }
        Map<String, Object> beforeSnapshot = snapshot(task);
        task.setTitle(request.title());
        task.setDescription(request.description());
        task = repo.save(task);
        TaskResponse response = toResponse(task);
        auditSink.record(AuditSink.TASK_UPDATED, "task", id, detailJson(beforeSnapshot, snapshot(task)));
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        return response;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        if (task.getStatus() != WorkItemStatus.backlog) {
            throw new ConflictException("Can only delete tasks in backlog status");
        }
        auditSink.record(AuditSink.TASK_DELETED, "task", id, detailJson(snapshot(task), null));
        repo.delete(task);
        eventPublisher.publishRoadmapItemChanged("task", id, "deleted");
    }

    @Override
    @Transactional
    public TaskResponse start(UUID id) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);

        if (task.getStatus() == WorkItemStatus.in_progress) {
            WorkflowRun mostRecent = mostRecentRun(id)
                    .orElseThrow(() -> new ConflictException("Task is in progress but has no linked run"));
            if (!TERMINAL_STATUSES.contains(mostRecent.getStatus())) {
                throw new ConflictException(
                        "Cannot re-trigger: most recent run is still active (status: " + mostRecent.getStatus() + ")");
            }
        } else if (task.getStatus() != WorkItemStatus.backlog) {
            throw new ConflictException("Can only start tasks in backlog status");
        }

        StringBuilder featureRequest = new StringBuilder();
        featureRequest.append("## ").append(task.getTitle()).append("\n\n");
        featureRequest.append(task.getDescription());

        GraphTemplate featureDevTemplate = graphTemplateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow(() -> new NotFoundException("Feature Development template not found"));

        CreateRunRequest runRequest = new CreateRunRequest(
                featureDevTemplate.getId(),
                Map.of(
                        "software_project_id", task.getSoftwareProjectId().toString(),
                        "feature_request", featureRequest.toString()),
                task.getTitle(),
                null);

        RunResponse runResponse = runService.startRun(runRequest);

        WorkflowRun run = runRepo.findById(runResponse.id())
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runResponse.id()));
        run.setTaskId(task.getId());
        runRepo.save(run);

        task.setStatus(WorkItemStatus.in_progress);
        task = repo.save(task);
        TaskResponse response = toResponse(task);
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        return response;
    }

    @Override
    @Transactional
    public TaskResponse complete(UUID id) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        if (task.getStatus() != WorkItemStatus.in_progress) {
            throw new ConflictException("Can only complete tasks that are in progress");
        }
        WorkflowRun mostRecent =
                mostRecentRun(id).orElseThrow(() -> new ConflictException("Task has no linked workflow run"));
        if (!TERMINAL_STATUSES.contains(mostRecent.getStatus())) {
            throw new ConflictException(
                    "Cannot complete: most recent run is still active (status: " + mostRecent.getStatus() + ")");
        }

        task.setStatus(WorkItemStatus.done);
        task = repo.save(task);
        TaskResponse response = toResponse(task);
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RunSummary> listRuns(UUID id, Pageable pageable) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        Page<WorkflowRun> page = runRepo.findByTaskIdOrderByCreatedAtDesc(id, pageable);

        Set<UUID> templateIds =
                page.stream().map(WorkflowRun::getGraphTemplateId).collect(Collectors.toSet());
        Map<UUID, String> templateNames = graphTemplateRepo.findAllById(templateIds).stream()
                .collect(Collectors.toMap(GraphTemplate::getId, GraphTemplate::getName));

        SoftwareProjectRef projectRef = softwareProjectRepo
                .findById(task.getSoftwareProjectId())
                .map(this::toProjectRef)
                .orElse(null);

        return page.map(run -> new RunSummary(
                run.getId(),
                run.getGraphTemplateId(),
                templateNames.getOrDefault(run.getGraphTemplateId(), "Unknown"),
                run.getName(),
                run.getStatus().name(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCreatedAt(),
                projectRef));
    }

    private Optional<WorkflowRun> mostRecentRun(UUID taskId) {
        return runRepo.findByTaskIdOrderByCreatedAtDesc(taskId, PageRequest.of(0, 1)).stream()
                .findFirst();
    }

    private TaskResponse toResponse(Task t) {
        SoftwareProject project = softwareProjectRepo
                .findById(t.getSoftwareProjectId())
                .orElseThrow(() -> new NotFoundException(
                        "SoftwareProject not found for task " + t.getId() + ": " + t.getSoftwareProjectId()));
        SoftwareProjectRef projectRef = toProjectRef(project);
        List<RepoRef> repos = project.resolveRepos().stream()
                .map(g -> new RepoRef(g.getId(), g.getUrl(), RepoNameUtil.deriveRepoName(g.getUrl())))
                .toList();
        Optional<WorkflowRun> mostRecent = mostRecentRun(t.getId());
        UUID latestRunId = mostRecent.map(WorkflowRun::getId).orElse(null);
        String latestRunStatus = mostRecent.map(r -> r.getStatus().name()).orElse(null);
        return new TaskResponse(
                t.getId(),
                t.getStoryId(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus().name(),
                projectRef,
                repos,
                latestRunId,
                latestRunStatus,
                t.getCreatedAt(),
                t.getUpdatedAt());
    }

    private SoftwareProjectRef toProjectRef(SoftwareProject project) {
        String type = (project instanceof RepoGroup) ? "repo_group" : "git_repo";
        return new SoftwareProjectRef(project.getId(), type, project.getName());
    }

    private Story findStoryOrThrow(UUID storyId) {
        return storyRepo.findById(storyId).orElseThrow(() -> new NotFoundException("Story not found: " + storyId));
    }

    private Epic findEpicOrThrow(UUID epicId) {
        return epicRepo.findById(epicId).orElseThrow(() -> new NotFoundException("Epic not found: " + epicId));
    }

    private Task findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Task not found: " + id));
    }

    private Map<String, Object> snapshot(Task t) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("story_id", t.getStoryId() != null ? t.getStoryId().toString() : null);
        snap.put("title", t.getTitle());
        snap.put("description", t.getDescription());
        snap.put("status", t.getStatus() != null ? t.getStatus().name() : null);
        snap.put(
                "software_project_id",
                t.getSoftwareProjectId() != null ? t.getSoftwareProjectId().toString() : null);
        return snap;
    }

    private String detailJson(Object before, Object after) {
        return AuditDetail.json(objectMapper, before, after);
    }
}
