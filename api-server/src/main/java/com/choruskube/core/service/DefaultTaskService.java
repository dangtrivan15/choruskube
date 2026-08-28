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
import com.choruskube.core.exception.InvalidStatusTransitionException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultTaskService implements TaskService {

    private static final Set<WorkflowRunStatus> TERMINAL_STATUSES =
            Set.of(WorkflowRunStatus.completed, WorkflowRunStatus.failed, WorkflowRunStatus.cancelled);

    /** Whitelist for {@link #updateStatus} — the public/request-scoped path. */
    private static final Set<Map.Entry<WorkItemStatus, WorkItemStatus>> PUBLIC_TRANSITIONS = Set.of(
            Map.entry(WorkItemStatus.backlog, WorkItemStatus.in_progress),
            Map.entry(WorkItemStatus.in_progress, WorkItemStatus.done),
            Map.entry(WorkItemStatus.in_progress, WorkItemStatus.backlog));

    /**
     * Whitelist for {@link #updateStatusInternal} — narrower than {@link #PUBLIC_TRANSITIONS}:
     * excludes {@code backlog->in_progress}, since starting a Task creates a brand new workflow
     * run (see {@link #start}) rather than just recording an outcome, which isn't this endpoint's
     * job (agents report on a run they are already inside).
     */
    private static final Set<Map.Entry<WorkItemStatus, WorkItemStatus>> INTERNAL_TRANSITIONS = Set.of(
            Map.entry(WorkItemStatus.in_progress, WorkItemStatus.done),
            Map.entry(WorkItemStatus.in_progress, WorkItemStatus.backlog));

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
    private final WorkItemDependencyService workItemDependencyService;
    // Populates `readiness` on list() responses via the same Epic-bounded assembly
    // the Roadmap Graph View uses, so the two can never disagree.
    private final EpicReadinessAssembler readinessAssembler;
    private final ScopeProvider scopeProvider;
    private final RunPullRequestRepository prRepo;
    // Needed for one thing only: refreshing a Task's in-memory state after its row lock is taken
    // (see startCore) — Spring Data has no refresh, and a locking finder alone returns the
    // already-loaded instance with its pre-lock state.
    private final EntityManager entityManager;

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
            ApplicationEventPublisher applicationEventPublisher,
            WorkItemDependencyService workItemDependencyService,
            EpicReadinessAssembler readinessAssembler,
            ScopeProvider scopeProvider,
            RunPullRequestRepository prRepo,
            EntityManager entityManager) {
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
        this.workItemDependencyService = workItemDependencyService;
        this.readinessAssembler = readinessAssembler;
        this.scopeProvider = scopeProvider;
        this.prRepo = prRepo;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public TaskResponse create(UUID storyId, TaskRequest request) {
        Story story = findStoryOrThrow(storyId);
        authService.checkOrgAccess("story", story.getId());
        Epic epic = findEpicOrThrow(story.getEpicId());
        Task task = persistTask(storyId, request, epic.getSoftwareProjectId());
        // Task is never top-level — org is always inherited from its parent Story.
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
        // Denormalized once at creation from the ancestor Epic — immutable afterwards.
        task.setSoftwareProjectId(softwareProjectId);
        // Create-time priority: absent (null) defaults to medium, mirroring the DB column default
        // and Epic/Story's own create-time priority handling.
        task.setPriority(request.priority() != null ? request.priority() : Priority.medium);
        return repo.save(task);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> list(UUID storyId) {
        Story story = findStoryOrThrow(storyId);
        authService.checkOrgAccess("story", storyId);
        return listWithReadiness(story, ReadinessAuthMode.PUBLIC, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> listInternal(UUID storyId, UUID runId, UUID runSoftwareProjectId) {
        Story story = findStoryOrThrow(storyId);
        Epic epic = findEpicOrThrow(story.getEpicId());
        authService.assertSameOrg("story", story.getId(), "workflow_run", runId);
        if (!epic.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException("Story " + storyId + " does not belong to the run's software project");
        }
        return listWithReadiness(story, ReadinessAuthMode.INTERNAL_RUN, runId);
    }

    /**
     * Shared body for {@link #list} and {@link #listInternal}: a Task's true blocker can sit in a
     * sibling Story under the same Epic, so this loads the OWNING EPIC's full
     * Story/Task set — not just this Story's own Tasks — the same "load this Epic's candidates"
     * helper {@link DefaultStoryService#list} uses, so the two list endpoints can never disagree
     * on what an Epic's candidate set is. Only this Story's own Tasks are returned.
     */
    private List<TaskResponse> listWithReadiness(Story story, ReadinessAuthMode mode, UUID contextId) {
        EpicReadinessAssembler.EpicCandidates candidates = readinessAssembler.loadEpicCandidates(story.getEpicId());
        EpicReadinessAssembler.Assembly assembly = readinessAssembler.assemble(
                candidates.candidateIds(), candidates.statusById(), candidates.parentOf(), mode, contextId);
        List<Task> ownTasks = candidates.tasksByStoryId().getOrDefault(story.getId(), List.of());
        return ownTasks.stream()
                .map(t -> toResponse(t, assembly.readinessById().get(t.getId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TaskResponse> list(WorkItemStatus status, Pageable pageable) {
        Specification<Task> spec = scopeProvider.scope(Task.class);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        Page<Task> page = repo.findAll(spec, pageable);
        List<TaskResponse> content =
                page.getContent().stream().map(this::toResponse).toList();
        return new PageImpl<>(content, pageable, page.getTotalElements());
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
        // work_item_dependency has no DB-level FK/ON DELETE CASCADE on this Task's id (it's a
        // polymorphic Story-or-Task reference), so any edge referencing it must be cleaned up here.
        workItemDependencyService.deleteAllReferencing(BlockableItemType.task, id);
        repo.delete(task);
        eventPublisher.publishRoadmapItemChanged("task", id, "deleted");
    }

    @Override
    @Transactional
    public TaskResponse start(UUID id) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);

        // One definition of ready, enforced rather than displayed: starting a blocked Task would
        // clone a base branch missing its blocker's work. The escape hatch is editing the
        // dependency, not bypassing this check.
        requireReady(task);

        return startCore(task, null);
    }

    // Plain REQUIRED, not REQUIRES_NEW. It used to need its own transaction because the Autopilot
    // tick was one long transaction wrapped around every start, so a failure here would have marked
    // the tick rollback-only and discarded both its bookkeeping and the starts that had already
    // succeeded. The tick now calls this from no transaction at all, one top-level transaction per
    // Task, so there is no parent to poison — and no parent whose snapshot predates these commits,
    // which is what forced the tick to re-read its own runs.
    @Override
    @Transactional
    public TaskResponse startForAutopilot(UUID id, UUID autopilotId) {
        Task task = findOrThrow(id);
        // No request context on a timer thread, so org is derived from the data rather than from a
        // caller's token — the same mechanism the agent path uses.
        authService.assertSameOrg("task", id, "autopilot", autopilotId);
        // AUTOPILOT mode because the public path resolves cross-Epic blockers through
        // request-scoped authorization that a timer thread does not have — and a cross-Epic
        // dependency is precisely the case the Autopilot exists to work through.
        requireReadyForAutopilot(task, autopilotId);
        return startCore(task, autopilotId);
    }

    /**
     * Shared body for the manual ({@link #start}) and Autopilot ({@link #startForAutopilot}) start
     * paths, from the status guard onward. {@code autopilotId} is null for a manual start, and is
     * stamped on the run so the Autopilot can tell its own in-flight work from a human's.
     */
    private TaskResponse startCore(Task task, UUID autopilotId) {
        // Both entry points serialise here, on the Task row itself. Nothing upstream does: the
        // Autopilot's tick lease is keyed on the Autopilot (pass vs pass), and the manual path
        // takes no lease and no lock at all, so under READ COMMITTED a tick and a user clicking
        // Start could both read `backlog`, both pass the guard below, and both commit.
        Task locked = repo.findWithLockById(task.getId())
                .orElseThrow(() -> new NotFoundException("Task not found: " + task.getId()));
        // The lock alone is not enough. It makes the DATABASE value authoritative, but the finder
        // returns the instance ALREADY in this persistence context — still carrying the status
        // read before the lock was granted, which is exactly the value the competing starter
        // invalidated while we were queued. Only an explicit refresh re-reads it.
        entityManager.refresh(locked);

        if (locked.getStatus() == WorkItemStatus.in_progress) {
            WorkflowRun mostRecent = mostRecentRun(locked.getId())
                    .orElseThrow(() -> new ConflictException("Task is in progress but has no linked run"));
            if (!TERMINAL_STATUSES.contains(mostRecent.getStatus())) {
                throw new ConflictException(
                        "Cannot re-trigger: most recent run is still active (status: " + mostRecent.getStatus() + ")");
            }
        } else if (locked.getStatus() != WorkItemStatus.backlog) {
            throw new ConflictException("Can only start tasks in backlog status");
        }

        StringBuilder featureRequest = new StringBuilder();
        featureRequest.append("## ").append(locked.getTitle()).append("\n\n");
        featureRequest.append(locked.getDescription());

        GraphTemplate featureDevTemplate = graphTemplateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow(() -> new NotFoundException("Feature Development template not found"));

        CreateRunRequest runRequest = new CreateRunRequest(
                featureDevTemplate.getId(),
                Map.of(
                        "software_project_id", locked.getSoftwareProjectId().toString(),
                        "feature_request", featureRequest.toString()),
                locked.getTitle(),
                null);

        // startRun performs the run-quota check itself; a second one here would be a duplicate.
        RunResponse runResponse = runService.startRun(runRequest);

        // CreateRunRequest carries neither field, so both attributions are stamped on the re-fetch.
        WorkflowRun run = runRepo.findById(runResponse.id())
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runResponse.id()));
        run.setTaskId(locked.getId());
        run.setAutopilotId(autopilotId);
        runRepo.save(run);

        locked.setStatus(WorkItemStatus.in_progress);
        Task saved = repo.save(locked);
        TaskResponse response = toResponse(saved);
        eventPublisher.publishRoadmapItemChanged(
                "task", saved.getId(), saved.getStatus().name());
        return response;
    }

    private void requireReady(Task task) {
        requireReady(task, ReadinessAuthMode.PUBLIC, null);
    }

    /**
     * {@link #requireReady} for the Autopilot: identical gate, resolved through the mode that does
     * not need a request context. Re-checked inside the start transaction because the frontier the
     * tick computed was assembled before the Task row was locked.
     */
    private void requireReadyForAutopilot(Task task, UUID autopilotId) {
        requireReady(task, ReadinessAuthMode.AUTOPILOT, autopilotId);
    }

    private void requireReady(Task task, ReadinessAuthMode mode, UUID contextId) {
        Story story = storyRepo
                .findById(task.getStoryId())
                .orElseThrow(() -> new NotFoundException("Story not found: " + task.getStoryId()));
        EpicReadinessAssembler.EpicCandidates candidates = readinessAssembler.loadEpicCandidates(story.getEpicId());
        EpicReadinessAssembler.Assembly assembly = readinessAssembler.assemble(
                candidates.candidateIds(), candidates.statusById(), candidates.parentOf(), mode, contextId);
        if (assembly.readinessById().get(task.getId()) != Readiness.BLOCKED) {
            return;
        }
        throw new ConflictException(
                "Cannot start a blocked Task. Waiting on: " + describeBlockers(task, story, candidates, assembly));
    }

    /**
     * Names the blockers actually worth acting on. Root causes only — an intermediate blocker that
     * is itself blocked is never reported, so the message points at work that can start now. The
     * block may sit on the Task, on its Story, or on its Epic, so each blocked ancestor contributes
     * its own root causes.
     */
    private String describeBlockers(
            Task task,
            Story story,
            EpicReadinessAssembler.EpicCandidates candidates,
            EpicReadinessAssembler.Assembly assembly) {
        LinkedHashSet<UUID> rootCauses = new LinkedHashSet<>();
        for (UUID id : List.of(task.getId(), story.getId(), story.getEpicId())) {
            if (assembly.readinessById().get(id) == Readiness.BLOCKED) {
                rootCauses.addAll(TransitiveReadinessResolver.rootCauseBlockersOf(
                        id, assembly.edges(), candidates.statusById()::get));
            }
        }
        List<String> titles =
                rootCauses.stream().map(this::titleOf).filter(Objects::nonNull).toList();
        // titleOf queries the repositories directly, unbounded by this Epic, so a root cause
        // outside it still resolves a title. This is reachable only when a root-cause id no longer
        // resolves at all — i.e. the item was deleted between the readiness walk and this lookup.
        return titles.isEmpty() ? "an unfinished dependency" : String.join(", ", titles);
    }

    /**
     * "done" means merged. A Task cannot close while a pull request from its most
     * recent run is still unmerged — a dependant started on a lying {@code done} would clone a
     * base branch without this Task's code.
     *
     * <p>Scoped to the most recent run rather than every run the Task ever had: retrying a Task
     * after a rejected PR is the normal path, and that abandoned PR will never merge. A Task
     * whose runs registered no PRs is vacuously satisfied and closes normally — which is what
     * keeps the board working on an OSS install with no GitHub credential configured.
     */
    private void requireMostRecentRunPullRequestsMerged(WorkflowRun mostRecent) {
        List<String> unmerged = prRepo.findByWorkflowRunId(mostRecent.getId()).stream()
                .filter(pr -> pr.getMergedAt() == null)
                .map(RunPullRequest::getPrUrl)
                .toList();
        if (unmerged.isEmpty()) {
            return;
        }
        throw new ConflictException("Cannot complete: this Task's most recent run has "
                + unmerged.size() + " unmerged pull request(s): " + String.join(", ", unmerged)
                + ". A Task closes automatically once they are merged.");
    }

    /** Resolves a blocker id to a display title, whichever tier it belongs to. */
    private String titleOf(UUID id) {
        return repo.findById(id)
                .map(Task::getTitle)
                .or(() -> storyRepo.findById(id).map(Story::getTitle))
                .or(() -> epicRepo.findById(id).map(Epic::getTitle))
                .orElse(null);
    }

    @Override
    @Transactional
    public TaskResponse complete(UUID id) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        return completeCore(task, null);
    }

    @Override
    @Transactional
    public TaskResponse closeForMergedPullRequests(UUID id) {
        Task task = findOrThrow(id);
        return completeCore(task, null);
    }

    @Override
    @Transactional
    public TaskResponse updateStatus(UUID id, WorkItemStatus target, UUID runId, String note) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        validateTransition(PUBLIC_TRANSITIONS, task.getStatus(), target);
        TaskResponse response =
                switch (target) {
                    case in_progress -> start(id);
                    case done -> completeCore(task, runId);
                    case backlog -> reopenCore(task, runId);
                    default -> throw new InvalidStatusTransitionException(task.getStatus(), target);
                };
        recordStatusChangeAudit(id, target, note);
        return response;
    }

    @Override
    @Transactional
    public TaskResponse updateStatusInternal(
            UUID id,
            WorkItemStatus target,
            UUID callingRunId,
            UUID runSoftwareProjectId,
            UUID outcomeRunId,
            String note) {
        Task task = findOrThrow(id);
        authService.assertSameOrg("task", task.getId(), "workflow_run", callingRunId);
        if (!task.getSoftwareProjectId().equals(runSoftwareProjectId)) {
            throw new ForbiddenException("Task " + id + " does not belong to the run's software project");
        }
        validateTransition(INTERNAL_TRANSITIONS, task.getStatus(), target);
        // Deliberately no recordStatusChangeAudit call here (unlike updateStatus): AuditSink is a
        // request-scoped-only sink (see PersistentAuditSink in the cloud overlay, which enriches
        // org/actor from TenantContext) — there is no tenant context on this JOB_SECRET path.
        // Mirrors the same omission in create(storyId, request, runId, runSoftwareProjectId)'s
        // internal overload above, which also skips auditSink.record for this reason.
        return switch (target) {
            case done -> completeCore(task, outcomeRunId);
            case backlog -> reopenCore(task, outcomeRunId);
            default -> throw new InvalidStatusTransitionException(task.getStatus(), target);
        };
    }

    /**
     * Shared {@code in_progress -> done} body for {@link #complete} (public, no runId check) and
     * {@link #updateStatus}/{@link #updateStatusInternal} (optionally verify {@code outcomeRunId}
     * matches the Task's most recent linked run before completing, guarding against a
     * stale/racing caller).
     */
    private TaskResponse completeCore(Task task, UUID outcomeRunId) {
        if (task.getStatus() != WorkItemStatus.in_progress) {
            throw new ConflictException("Can only complete tasks that are in progress");
        }
        WorkflowRun mostRecent =
                mostRecentRun(task.getId()).orElseThrow(() -> new ConflictException("Task has no linked workflow run"));
        if (!TERMINAL_STATUSES.contains(mostRecent.getStatus())) {
            throw new ConflictException(
                    "Cannot complete: most recent run is still active (status: " + mostRecent.getStatus() + ")");
        }
        if (outcomeRunId != null && !outcomeRunId.equals(mostRecent.getId())) {
            throw new ConflictException(
                    "runId " + outcomeRunId + " does not match the Task's most recent run " + mostRecent.getId());
        }
        requireMostRecentRunPullRequestsMerged(mostRecent);

        task.setStatus(WorkItemStatus.done);
        task = repo.save(task);
        TaskResponse response = toResponse(task);
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        return response;
    }

    /**
     * New {@code in_progress -> backlog} "reopen" transition — lets a Task be
     * retried after a failed/aborted run, gated the same way {@link #completeCore} gates
     * completion: the most recent run must be terminal.
     */
    private TaskResponse reopenCore(Task task, UUID outcomeRunId) {
        if (task.getStatus() != WorkItemStatus.in_progress) {
            throw new ConflictException("Can only reopen tasks that are in progress");
        }
        WorkflowRun mostRecent =
                mostRecentRun(task.getId()).orElseThrow(() -> new ConflictException("Task has no linked workflow run"));
        if (!TERMINAL_STATUSES.contains(mostRecent.getStatus())) {
            throw new ConflictException(
                    "Cannot reopen: most recent run is still active (status: " + mostRecent.getStatus() + ")");
        }
        if (outcomeRunId != null && !outcomeRunId.equals(mostRecent.getId())) {
            throw new ConflictException(
                    "runId " + outcomeRunId + " does not match the Task's most recent run " + mostRecent.getId());
        }

        task.setStatus(WorkItemStatus.backlog);
        task = repo.save(task);
        TaskResponse response = toResponse(task);
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        return response;
    }

    private static void validateTransition(
            Set<Map.Entry<WorkItemStatus, WorkItemStatus>> whitelist, WorkItemStatus current, WorkItemStatus target) {
        if (!whitelist.contains(Map.entry(current, target))) {
            throw new InvalidStatusTransitionException(current, target);
        }
    }

    private void recordStatusChangeAudit(UUID id, WorkItemStatus target, String note) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", target.name());
        if (note != null) {
            detail.put("note", note);
        }
        auditSink.record(AuditSink.TASK_STATUS_CHANGED, "task", id, detailJson(null, detail));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RunSummary> listRuns(UUID id, Pageable pageable) {
        Task task = findOrThrow(id);
        authService.checkOrgAccess("task", id);
        return runsPage(task, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RunSummary> listRunsInternal(UUID id, Pageable pageable) {
        Task task = findOrThrow(id);
        return runsPage(task, pageable);
    }

    private Page<RunSummary> runsPage(Task task, Pageable pageable) {
        UUID id = task.getId();
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

    /** Single-item read paths (create/update/get/start/...) — readiness stays {@code null} here
     * (real readiness is scoped to the flat list endpoints and the Roadmap Graph View
     * only); {@code recentRuns}/{@code totalRunCount} stay empty/zero here too
     * (unrelated to this feature, only the Roadmap Graph View embeds run history). */
    private TaskResponse toResponse(Task t) {
        return toResponse(t, null);
    }

    private TaskResponse toResponse(Task t, Readiness readiness) {
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
                readiness,
                List.of(), // recentRuns: only embedded by RoadmapGraphService
                0L, // totalRunCount: ditto
                t.getCreatedAt(),
                t.getUpdatedAt(),
                t.getPriority().name());
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
