package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.SharedContextTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalUpdateRunStatusRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Closing a Task as delivered starts a Delivery Check run with the named pull requests registered
 * on it, and that run's finish closes the Task the way every Task closes. Not {@code @Transactional}:
 * the run's workflow starts only after a real commit.
 */
class TaskDeliveryCheckServiceIntegrationTest extends SharedContextTest {

    @Autowired
    private TaskDeliveryCheckService service;

    @Autowired
    private InternalRunService internalRunService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private RunPullRequestRepository prRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    private CommittedFixtureCleaner cleaner;
    private GitRepo repo;
    private String ownerRepo;
    private UUID storyId;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        when(workflowClient.newUntypedWorkflowStub(any(), any(io.temporal.client.WorkflowOptions.class)))
                .thenReturn(mock(WorkflowStub.class));
        when(workflowClient.newUntypedWorkflowStub(any())).thenReturn(mock(WorkflowStub.class));
        when(workflowClientRegistry.clientFor(any())).thenReturn(workflowClient);

        String url = "https://github.com/test/delivery-check-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        repo = gitRepoRepo.save(r);
        cleaner.trackSoftwareProject(repo.getId());
        ownerRepo = RepoNameUtil.githubOwnerRepo(url).orElseThrow();

        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, repo.getId()), null);
        cleaner.trackEpic(epic.id());
        // Never designed: the close records delivered work, so no spec is needed.
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
        storyId = story.id();
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
    }

    @Test
    void aBacklogTask_startsADeliveryCheckRunWithEachPullRequestRegisteredOnce() {
        UUID taskId = task("Delete run artifacts after the retention period");

        TaskResponse response = service.start(
                taskId,
                List.of(prUrl(12), prUrl(12) + "/files", " " + prUrl(13) + " "),
                "  Delivered by two pull requests made outside ChorusKube.  ");

        assertThat(response.status()).isEqualTo("in_progress");
        WorkflowRun run = runRepo.findById(response.latestRunId()).orElseThrow();
        assertThat(run.getTaskId()).isEqualTo(taskId);
        assertThat(graphTemplateRepo
                        .findById(run.getGraphTemplateId())
                        .orElseThrow()
                        .getGraphId())
                .isEqualTo(GraphIds.DELIVERY_CHECK);
        assertThat(run.getName()).startsWith("Delivery check: ");
        assertThat(run.getInputs()).contains("Delivered by two pull requests made outside ChorusKube.");
        assertThat(run.getStorySpecVersion()).isNull();

        List<RunPullRequest> rows = prRepo.findByWorkflowRunId(run.getId());
        assertThat(rows).extracting(RunPullRequest::getPrUrl).containsExactlyInAnyOrder(canonical(12), canonical(13));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getGitRepoId()).isEqualTo(repo.getId());
            assertThat(row.getMergedAt()).isNull();
            assertThat(row.getNodeExecutionId()).isNull();
        });
    }

    @Test
    void aBlockedTask_canStillBeClosedAsDelivered() {
        UUID blocker = task("Blocker");
        UUID blocked = task("Blocked");
        dependencyService.create(new CreateDependencyRequest("task", blocker, "task", blocked));

        TaskResponse response = service.start(blocked, List.of(prUrl(5)), "Landed already.");

        assertThat(response.status()).isEqualTo("in_progress");
    }

    @Test
    void aPullRequestOutsideTheTasksRepositories_isRefused_andNothingStarts() {
        UUID taskId = task("Task");

        assertThatThrownBy(() -> service.start(
                        taskId, List.of(prUrl(1), "https://github.com/someone-else/elsewhere/pull/2"), "note"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("https://github.com/someone-else/elsewhere/pull/2 is not in a repository")
                .hasMessageContaining(ownerRepo);

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.backlog);
        assertThat(runRepo.findByTaskIdOrderByCreatedAtDesc(taskId, org.springframework.data.domain.Pageable.unpaged()))
                .isEmpty();
    }

    @Test
    void aUrlThatIsNotAPullRequest_isRefused() {
        UUID taskId = task("Task");

        assertThatThrownBy(() -> service.start(taskId, List.of("https://github.com/" + ownerRepo + "/issues/3"), "n"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("is not a github.com pull request URL");
    }

    @Test
    void aDoneTask_isRefused() {
        UUID taskId = task("Task");
        setStatus(taskId, WorkItemStatus.done);

        assertThatThrownBy(() -> service.start(taskId, List.of(prUrl(1)), "note"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already done");
    }

    @Test
    void aTaskWhoseRunIsStillActive_isRefused() {
        UUID taskId = task("Task");
        TaskResponse first = service.start(taskId, List.of(prUrl(1)), "note");

        assertThatThrownBy(() -> service.start(taskId, List.of(prUrl(2)), "note"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("still active");
        assertThat(prRepo.findByWorkflowRunId(first.latestRunId())).hasSize(1);
    }

    @Test
    void aTaskWhoseLatestRunEnded_canBeClosedAsDeliveredAgain() {
        UUID taskId = task("Task");
        TaskResponse first = service.start(taskId, List.of(prUrl(1)), "note");
        internalRunService.updateRunStatus(first.latestRunId(), new InternalUpdateRunStatusRequest("cancelled"));

        TaskResponse second = service.start(taskId, List.of(prUrl(2)), "corrected the pull request");

        assertThat(second.latestRunId()).isNotEqualTo(first.latestRunId());
        assertThat(second.status()).isEqualTo("in_progress");
    }

    /** The Worker's verification records merged_at; the run's finish then settles the Task as usual. */
    @Test
    void aDeliveryCheckWhosePullRequestsVerified_closesTheTaskWhenItsRunFinishes() {
        UUID taskId = task("Task");
        TaskResponse started = service.start(taskId, List.of(prUrl(1)), "note");
        for (RunPullRequest row : prRepo.findByWorkflowRunId(started.latestRunId())) {
            row.setMergedAt(Instant.now());
            prRepo.save(row);
        }

        internalRunService.updateRunStatus(started.latestRunId(), new InternalUpdateRunStatusRequest("completed"));

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
        assertThat(runRepo.findById(started.latestRunId()).orElseThrow().getStatus())
                .isEqualTo(WorkflowRunStatus.completed);
    }

    @Test
    void aDeliveryCheckThatEndsUnverified_leavesTheTaskOpen() {
        UUID taskId = task("Task");
        TaskResponse started = service.start(taskId, List.of(prUrl(1)), "note");

        internalRunService.updateRunStatus(started.latestRunId(), new InternalUpdateRunStatusRequest("cancelled"));

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.in_progress);
    }

    private UUID task(String title) {
        return taskService
                .create(storyId, new TaskRequest(title, title + " desc"))
                .id();
    }

    private void setStatus(UUID taskId, WorkItemStatus status) {
        Task task = taskRepo.findById(taskId).orElseThrow();
        task.setStatus(status);
        taskRepo.save(task);
    }

    private WorkItemStatus statusOf(UUID taskId) {
        return taskRepo.findById(taskId).orElseThrow().getStatus();
    }

    private String prUrl(int number) {
        return "https://github.com/" + ownerRepo + "/pull/" + number;
    }

    private String canonical(int number) {
        return prUrl(number);
    }
}
