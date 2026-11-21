package com.choruskube.core.service;

import com.choruskube.core.config.BaseDeliveryCheckSeeder;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.TemplateGraphIdResolver;
import com.choruskube.core.dto.CreateRunRequest;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.observability.AuditDetail;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.choruskube.core.util.RepoNameUtil.GitHubPullRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a Task whose work merged outside ChorusKube. It starts a Delivery Check run of the Task
 * with the named pull requests registered on it; the run's gate has the Worker verify each one is
 * merged into its repository's default branch, and the run's finish closes the Task the way every
 * Task closes. Nothing here decides that the work merged — this server never asks GitHub.
 */
@Service
public class TaskDeliveryCheckService {

    private static final String RUN_NAME_PREFIX = "Delivery check: ";

    private record Claim(GitHubPullRequest pullRequest, GitRepo repo) {}

    private final TaskRepository taskRepo;
    private final WorkflowRunRepository runRepo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final GraphTemplateRepository graphTemplateRepo;
    private final TemplateGraphIdResolver graphIdResolver;
    private final RunPullRequestRepository prRepo;
    private final RunService runService;
    private final TaskService taskService;
    private final AuthorizationService authService;
    private final RunEventPublisher eventPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public TaskDeliveryCheckService(
            TaskRepository taskRepo,
            WorkflowRunRepository runRepo,
            SoftwareProjectRepository softwareProjectRepo,
            GraphTemplateRepository graphTemplateRepo,
            TemplateGraphIdResolver graphIdResolver,
            RunPullRequestRepository prRepo,
            RunService runService,
            TaskService taskService,
            AuthorizationService authService,
            RunEventPublisher eventPublisher,
            ApplicationEventPublisher applicationEventPublisher,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            EntityManager entityManager) {
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.graphTemplateRepo = graphTemplateRepo;
        this.graphIdResolver = graphIdResolver;
        this.prRepo = prRepo;
        this.runService = runService;
        this.taskService = taskService;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.applicationEventPublisher = applicationEventPublisher;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    /**
     * Neither the Task's blockers nor its Story's design are checked: they decide when work may
     * start, and this records work that already landed.
     */
    @Transactional
    public TaskResponse start(UUID taskId, List<String> pullRequestUrls, String note) {
        Task task = taskRepo.findById(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        authService.checkOrgAccess("task", taskId);
        // Serialised with a start, a settlement and a completion on the Task row, as they are.
        Task locked =
                taskRepo.findWithLockById(taskId).orElseThrow(() -> new NotFoundException("Task not found: " + taskId));
        entityManager.refresh(locked);
        requireClosable(locked);

        SoftwareProject project = softwareProjectRepo
                .findById(locked.getSoftwareProjectId())
                .orElseThrow(
                        () -> new NotFoundException("Software project not found: " + locked.getSoftwareProjectId()));
        List<Claim> claims = resolveClaims(pullRequestUrls, project);

        GraphTemplate template = graphTemplateRepo
                .findFirstByGraphIdOrderByVersionDesc(graphIdResolver.resolve(GraphIds.DELIVERY_CHECK))
                .orElseThrow(() -> new NotFoundException("Delivery Check template not found"));
        RunResponse started = runService.startRun(new CreateRunRequest(
                template.getId(),
                Map.of(
                        "software_project_id",
                        locked.getSoftwareProjectId().toString(),
                        BaseDeliveryCheckSeeder.NOTE_INPUT,
                        note.strip()),
                runName(locked.getTitle()),
                null));
        // The workflow starts after this transaction commits, so the rows below exist before its gate.
        WorkflowRun run = runRepo.findById(started.id())
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + started.id()));
        run.setTaskId(locked.getId());
        runRepo.save(run);
        for (Claim claim : claims) {
            register(run.getId(), claim);
        }
        eventPublisher.publishPullRequestCreated(run.getId());

        locked.setStatus(WorkItemStatus.in_progress);
        taskRepo.save(locked);
        eventPublisher.publishRoadmapItemChanged(
                "task", locked.getId(), locked.getStatus().name());
        audit(locked.getId(), run.getId(), claims, note.strip());
        return taskService.get(locked.getId());
    }

    private void requireClosable(Task task) {
        if (task.getStatus() == WorkItemStatus.done) {
            throw new ConflictException("This Task is already done");
        }
        if (task.getStatus() == WorkItemStatus.in_progress) {
            Optional<WorkflowRun> latest =
                    runRepo.findByTaskIdOrderByCreatedAtDesc(task.getId(), PageRequest.of(0, 1)).stream()
                            .findFirst();
            if (latest.isPresent()
                    && !WorkflowRunStatusGroups.TERMINAL.contains(latest.get().getStatus())) {
                throw new ConflictException("Cannot close as delivered while the Task's run is still active (status: "
                        + latest.get().getStatus() + "); cancel it first");
            }
        } else if (task.getStatus() != WorkItemStatus.backlog) {
            throw new ConflictException("Cannot close a Task in status " + task.getStatus() + " as delivered");
        }
    }

    /**
     * Each URL must name a pull request in one of the repositories the run will span, because the
     * run's GitHub token reaches no other, and the Worker reads every one with it.
     */
    private List<Claim> resolveClaims(List<String> pullRequestUrls, SoftwareProject project) {
        Map<String, GitRepo> reposByOwnerRepo = new LinkedHashMap<>();
        for (GitRepo repo : project.resolveRepos()) {
            RepoNameUtil.githubOwnerRepo(repo.getUrl())
                    .ifPresent(ownerRepo -> reposByOwnerRepo.putIfAbsent(ownerRepo.toLowerCase(Locale.ROOT), repo));
        }
        Map<String, Claim> claims = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (String url : pullRequestUrls) {
            Optional<GitHubPullRequest> parsed = RepoNameUtil.githubPullRequest(url);
            if (parsed.isEmpty()) {
                problems.add("'" + url.strip() + "' is not a github.com pull request URL");
                continue;
            }
            GitHubPullRequest pr = parsed.get();
            GitRepo repo = reposByOwnerRepo.get(pr.ownerRepo().toLowerCase(Locale.ROOT));
            if (repo == null) {
                problems.add(pr.url() + " is not in a repository of this Task's software project");
                continue;
            }
            String key = pr.ownerRepo().toLowerCase(Locale.ROOT) + "#" + pr.number();
            claims.putIfAbsent(key, new Claim(pr, repo));
        }
        if (!problems.isEmpty()) {
            throw new BadRequestException(String.join("; ", problems)
                    + (reposByOwnerRepo.isEmpty()
                            ? ". This Task's software project has no github.com repository."
                            : ". Its repositories: " + String.join(", ", repoNames(reposByOwnerRepo)) + "."));
        }
        return List.copyOf(claims.values());
    }

    private static List<String> repoNames(Map<String, GitRepo> reposByOwnerRepo) {
        return reposByOwnerRepo.values().stream()
                .map(repo -> RepoNameUtil.githubOwnerRepo(repo.getUrl()).orElse(repo.getUrl()))
                .toList();
    }

    private void register(UUID runId, Claim claim) {
        RunPullRequest row = new RunPullRequest();
        row.setWorkflowRunId(runId);
        row.setGitRepoId(claim.repo().getId());
        row.setPrUrl(claim.pullRequest().url());
        row.setPrNumber(claim.pullRequest().number());
        row.setRepoName(claim.repo().getName());
        row = prRepo.save(row);
        applicationEventPublisher.publishEvent(
                MappableCreated.withParent("run_pull_request", row.getId(), "workflow_run", runId));
    }

    private void audit(UUID taskId, UUID runId, List<Claim> claims, String note) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", runId.toString());
        detail.put(
                "pullRequests", claims.stream().map(c -> c.pullRequest().url()).toList());
        detail.put("note", note);
        auditSink.record(
                AuditSink.TASK_DELIVERY_CHECK_STARTED, "task", taskId, AuditDetail.json(objectMapper, null, detail));
    }

    // The run service trims a run's name to its display width; the prefix is what survives.
    private static String runName(String title) {
        return RUN_NAME_PREFIX + title;
    }
}
