package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalUpdateRunStatusRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * A run finishing and its Task closing are one state change: the Task closes in the transaction
 * that moves its run to a terminal status, and only once every pull request the run registered
 * has merged.
 *
 * <p>Deliberately NOT {@code @Transactional}. "Same transaction" and "after commit" are only
 * observable when something actually commits, so every write here does, and {@link
 * CommittedFixtureCleaner} removes it afterwards.
 */
public class TaskSettlementIntegrationTest extends BaseTest {

    @Autowired
    private InternalRunService internalRunService;

    @Autowired
    private RunService runService;

    @Autowired
    private TaskSettlementService taskSettlementService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private EpicService epicService;

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
    private TaskGithubIssueRepository taskGithubIssueRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private GitHubAppService gitHubAppService;

    @MockitoBean
    private GitHubCredentialResolver gitHubCredentialResolver;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    private CommittedFixtureCleaner cleaner;
    private final List<UUID> graphTemplateIds = new ArrayList<>();
    private GitRepo repo;
    private UUID graphTemplateId;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        WorkflowClient client = mock(WorkflowClient.class);
        when(client.newUntypedWorkflowStub(any())).thenReturn(mock(WorkflowStub.class));
        when(workflowClientRegistry.clientFor(any())).thenReturn(client);
        when(gitHubCredentialResolver.getTokenForRepo(any())).thenReturn("t0ken");

        repo = makeRepo();
        GraphTemplate template = new GraphTemplate();
        template.setName("Task settlement template");
        template.setGraphId("task-settlement-" + UUID.randomUUID());
        template.setVersion(1);
        graphTemplateId = graphTemplateRepo.save(template).getId();
        graphTemplateIds.add(graphTemplateId);
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
        graphTemplateIds.forEach(id -> jdbc.update("DELETE FROM graph_template WHERE id = ?", id));
    }

    @Test
    void aRunFinishingWithEveryPullRequestMerged_closesItsTask() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());
        registerPullRequest(run, 2, Instant.now());

        finish(run, "completed");

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(WorkflowRunStatus.completed);
    }

    @Test
    void anUnmergedPullRequest_leavesTheTaskOpenAndTheRunStillFinishes() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());
        registerPullRequest(run, 2, null);

        finish(run, "completed");

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.in_progress);
        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(WorkflowRunStatus.completed);
    }

    /** Nothing merged, so nothing says the work landed. */
    @Test
    void aRunThatOpenedNoPullRequest_leavesTheTaskOpen() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);

        finish(run, "completed");

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.in_progress);
    }

    /** A late report for a run the Task has moved past: only the latest run may close the Task. */
    @Test
    void aSupersededRunReportingLate_leavesTheTaskToItsLatestRun() {
        UUID taskId = inProgressTask();
        WorkflowRun superseded = runFor(taskId, WorkflowRunStatus.cancelled);
        registerPullRequest(superseded, 1, Instant.now());
        runFor(taskId);

        finish(superseded, "cancelled");

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.in_progress);
    }

    @Test
    void reportingTheSameFinishTwice_closesTheTaskOnce() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());

        finish(run, "completed");
        finish(run, "completed");

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
        verify(runEventPublisher, times(1)).publishRoadmapItemChanged("task", taskId, "done");
    }

    @Test
    void aTaskSomeoneAlreadyClosed_doesNotStopTheRunFinishing() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());
        Task task = taskRepo.findById(taskId).orElseThrow();
        task.setStatus(WorkItemStatus.done);
        taskRepo.save(task);

        finish(run, "completed");

        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(WorkflowRunStatus.completed);
        verify(runEventPublisher, never()).publishRoadmapItemChanged("task", taskId, "done");
    }

    /**
     * The other half of "one state change": a closure that fails takes the finish down with it, so
     * the orchestrator's retry finds the run still open rather than a finished run whose Task can
     * now only be closed by hand.
     */
    @Test
    void aClosureThatFails_rollsTheRunFinishBack() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());
        doThrow(new IllegalStateException("broker down"))
                .when(runEventPublisher)
                .publishRoadmapItemChanged("task", taskId, "done");

        assertThatThrownBy(() -> finish(run, "completed")).isInstanceOf(IllegalStateException.class);

        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(WorkflowRunStatus.running);
        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.in_progress);
    }

    @Test
    void cancellingARunWhoseEveryPullRequestMerged_closesItsTask() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());

        runService.cancelRun(run.getId());

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
        assertThat(runRepo.findById(run.getId()).orElseThrow().getStatus()).isEqualTo(WorkflowRunStatus.cancelled);
    }

    /** The GitHub call waits for the commit: it must not hold the run's transaction open. */
    @Test
    void theLinkedGithubIssue_closesAfterTheCommit() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());
        TaskGithubIssue issue = new TaskGithubIssue();
        issue.setTaskId(taskId);
        issue.setGitRepoId(repo.getId());
        issue.setIssueNumber(42);
        issue.setIssueUrl(repo.getUrl() + "/issues/42");
        taskGithubIssueRepo.save(issue);

        finish(run, "completed");

        verify(gitHubAppService).closeIssue("t0ken", repo.getName(), 42);
        assertThat(taskGithubIssueRepo.findByTaskId(taskId).orElseThrow().getState())
                .isEqualTo(GithubIssueState.closed);
    }

    /**
     * Fails after the Task is already marked done, so only deferring the GitHub call to after commit
     * keeps it from closing the issue of a finish that is then rolled back.
     */
    @Test
    void aRolledBackFinish_neverClosesTheLinkedGithubIssue() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);
        registerPullRequest(run, 1, Instant.now());
        TaskGithubIssue issue = new TaskGithubIssue();
        issue.setTaskId(taskId);
        issue.setGitRepoId(repo.getId());
        issue.setIssueNumber(43);
        issue.setIssueUrl(repo.getUrl() + "/issues/43");
        taskGithubIssueRepo.save(issue);
        doThrow(new IllegalStateException("broker down"))
                .when(runEventPublisher)
                .publishRunStatusChanged(run.getId(), "completed");

        assertThatThrownBy(() -> finish(run, "completed")).isInstanceOf(IllegalStateException.class);

        verify(gitHubAppService, never()).closeIssue(anyString(), anyString(), anyInt());
    }

    @Test
    void settlingOutsideATransaction_isRefused() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId);

        assertThatThrownBy(() -> taskSettlementService.closeIfSettledBy(run))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void finish(WorkflowRun run, String status) {
        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest(status));
    }

    private WorkItemStatus statusOf(UUID taskId) {
        return taskRepo.findById(taskId).orElseThrow().getStatus();
    }

    private UUID inProgressTask() {
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, repo.getId()), null);
        cleaner.trackEpic(epic.id());
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
        UUID taskId = taskService
                .create(story.id(), new TaskRequest("Task", "Task desc"))
                .id();
        Task task = taskRepo.findById(taskId).orElseThrow();
        task.setStatus(WorkItemStatus.in_progress);
        taskRepo.save(task);
        return taskId;
    }

    private WorkflowRun runFor(UUID taskId) {
        return runFor(taskId, WorkflowRunStatus.running);
    }

    private WorkflowRun runFor(UUID taskId, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(graphTemplateId);
        run.setTaskId(taskId);
        run.setStatus(status);
        run.setExternalRunId("choruskube-run-" + UUID.randomUUID());
        return runRepo.save(run);
    }

    private void registerPullRequest(WorkflowRun run, int number, Instant mergedAt) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(run.getId());
        pr.setGitRepoId(repo.getId());
        pr.setPrUrl(repo.getUrl().replace(".git", "") + "/pull/" + number);
        pr.setPrNumber(number);
        pr.setMergedAt(mergedAt);
        prRepo.save(pr);
    }

    private GitRepo makeRepo() {
        String url = "https://github.com/test/task-settlement-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo saved = gitRepoRepo.save(r);
        cleaner.trackSoftwareProject(saved.getId());
        return saved;
    }
}
