package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.CloseTaskIssueWorkflowInput;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.TaskGithubIssueCloser.RecordOutcome;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Completing a Task starts the close of its linked GitHub issue as a workflow, once and only after
 * the completion commits; the server itself never calls GitHub.
 *
 * <p>Deliberately NOT {@code @Transactional}: {@code afterCommit} only fires when something
 * commits, so every write here does, and {@link CommittedFixtureCleaner} removes it afterwards.
 */
public class TaskIssueCloseIntegrationTest extends BaseTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private TaskGithubIssueCloser issueCloser;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private TaskGithubIssueRepository taskGithubIssueRepo;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${temporal.namespace}")
    private String namespace;

    @Value("${temporal.task-queue}")
    private String orchestratorQueue;

    @Value("${temporal.worker-task-queue:${temporal.task-queue}}")
    private String workerQueue;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private GitHubAppService gitHubAppService;

    @MockitoBean
    private GitHubCredentialResolver gitHubCredentialResolver;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    @MockitoSpyBean
    private RunPlacementService runPlacementService;

    private CommittedFixtureCleaner cleaner;
    private final List<UUID> graphTemplateIds = new ArrayList<>();
    private WorkflowClient workflowClient;
    private WorkflowStub issueCloseStub;
    private GitRepo repo;
    private UUID graphTemplateId;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        workflowClient = mock(WorkflowClient.class);
        issueCloseStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(eq("CloseTaskIssueWorkflow"), any(WorkflowOptions.class)))
                .thenReturn(issueCloseStub);
        when(workflowClientRegistry.clientFor(any())).thenReturn(workflowClient);

        repo = makeRepo();
        GraphTemplate template = new GraphTemplate();
        template.setName("Task issue close template");
        template.setGraphId("task-issue-close-" + UUID.randomUUID());
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
    void manualComplete_startsTheCloseOnceAfterCommit() {
        UUID taskId = inProgressTask();
        WorkflowRun run = finishedRunFor(taskId);
        link(taskId, 42, GithubIssueState.open);

        taskService.complete(taskId);

        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        verify(workflowClientRegistry).clientFor(namespace);
        verify(workflowClient, times(1)).newUntypedWorkflowStub(eq("CloseTaskIssueWorkflow"), options.capture());
        assertThat(options.getValue().getWorkflowId()).isEqualTo("choruskube-task-issue-close-" + taskId);
        assertThat(options.getValue().getTaskQueue()).isEqualTo(orchestratorQueue);
        assertThat(options.getValue().getWorkflowExecutionTimeout()).isNull();
        assertThat(options.getValue().getWorkflowRunTimeout()).isNull();
        assertThat(startedInput())
                .isEqualTo(new CloseTaskIssueWorkflowInput(taskId, run.getId(), repo.getName(), 42, workerQueue));
        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
        verifyNoInteractions(gitHubAppService, gitHubCredentialResolver);
    }

    @Test
    void publicStatusUpdateToDone_startsTheCloseOnce() {
        UUID taskId = inProgressTask();
        WorkflowRun run = finishedRunFor(taskId);
        link(taskId, 43, GithubIssueState.open);

        taskService.updateStatus(taskId, WorkItemStatus.done, run.getId(), null);

        assertThat(startedInput().runId()).isEqualTo(run.getId());
        verifyNoInteractions(gitHubAppService, gitHubCredentialResolver);
    }

    @Test
    void internalStatusUpdateToDone_startsTheCloseOnce() {
        UUID taskId = inProgressTask();
        WorkflowRun run = finishedRunFor(taskId);
        link(taskId, 44, GithubIssueState.open);

        taskService.updateStatusInternal(
                taskId, WorkItemStatus.done, run.getId(), repo.getId(), run.getId(), "agent done");

        assertThat(startedInput().runId()).isEqualTo(run.getId());
        verifyNoInteractions(gitHubAppService, gitHubCredentialResolver);
    }

    @Test
    void noLinkage_startsNothing() {
        UUID taskId = inProgressTask();
        finishedRunFor(taskId);

        taskService.complete(taskId);

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
        verifyNoInteractions(workflowClient);
    }

    @Test
    void alreadyClosedLinkage_startsNothing() {
        UUID taskId = inProgressTask();
        finishedRunFor(taskId);
        link(taskId, 45, GithubIssueState.closed);

        taskService.complete(taskId);

        verifyNoInteractions(workflowClient);
    }

    @Test
    void aRolledBackCompletion_startsNothing() {
        UUID taskId = inProgressTask();
        finishedRunFor(taskId);
        link(taskId, 46, GithubIssueState.open);

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            taskService.complete(taskId);
            status.setRollbackOnly();
        });

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.in_progress);
        verifyNoInteractions(workflowClient);
    }

    @Test
    void aDuplicateStart_isSwallowed() {
        UUID taskId = inProgressTask();
        finishedRunFor(taskId);
        link(taskId, 47, GithubIssueState.open);
        when(issueCloseStub.start(any()))
                .thenThrow(new WorkflowExecutionAlreadyStarted(
                        WorkflowExecution.newBuilder()
                                .setWorkflowId("choruskube-task-issue-close-" + taskId)
                                .build(),
                        "CloseTaskIssueWorkflow",
                        null));

        assertThatCode(() -> taskService.complete(taskId)).doesNotThrowAnyException();

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
    }

    @Test
    void aFailedStart_leavesTheTaskDone() {
        UUID taskId = inProgressTask();
        finishedRunFor(taskId);
        link(taskId, 48, GithubIssueState.open);
        when(issueCloseStub.start(any())).thenThrow(new RuntimeException("temporal down"));

        assertThatCode(() -> taskService.complete(taskId)).doesNotThrowAnyException();

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
    }

    @Test
    void aFailedPlacement_leavesTheTaskDoneAndStartsNothing() {
        UUID taskId = inProgressTask();
        WorkflowRun run = finishedRunFor(taskId);
        link(taskId, 49, GithubIssueState.open);
        doThrow(new IllegalStateException("no placement"))
                .when(runPlacementService)
                .placeFor(run.getId());

        assertThatCode(() -> taskService.complete(taskId)).doesNotThrowAnyException();

        assertThat(statusOf(taskId)).isEqualTo(WorkItemStatus.done);
        verify(issueCloseStub, never()).start(any());
    }

    @Test
    void closeAfterCommit_outsideATransaction_isAProgrammingError() {
        assertThatThrownBy(() -> issueCloser.closeAfterCommit(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordClosed_openLinkage_isRecorded() {
        UUID taskId = inProgressTask();
        link(taskId, 50, GithubIssueState.open);

        assertThat(issueCloser.recordClosed(taskId, 50)).isEqualTo(RecordOutcome.RECORDED);

        TaskGithubIssue reloaded = taskGithubIssueRepo.findByTaskId(taskId).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(GithubIssueState.closed);
        assertThat(reloaded.getClosedAt()).isNotNull();
    }

    @Test
    void recordClosed_twice_keepsTheFirstClosedAt() {
        UUID taskId = inProgressTask();
        link(taskId, 51, GithubIssueState.open);
        issueCloser.recordClosed(taskId, 51);
        Instant firstClosedAt =
                taskGithubIssueRepo.findByTaskId(taskId).orElseThrow().getClosedAt();

        assertThat(issueCloser.recordClosed(taskId, 51)).isEqualTo(RecordOutcome.ALREADY_CLOSED);

        assertThat(taskGithubIssueRepo.findByTaskId(taskId).orElseThrow().getClosedAt())
                .isEqualTo(firstClosedAt);
    }

    @Test
    void recordClosed_otherIssueNumber_isAMismatchAndLeavesTheRowOpen() {
        UUID taskId = inProgressTask();
        link(taskId, 52, GithubIssueState.open);

        assertThat(issueCloser.recordClosed(taskId, 53)).isEqualTo(RecordOutcome.MISMATCH);

        TaskGithubIssue reloaded = taskGithubIssueRepo.findByTaskId(taskId).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(GithubIssueState.open);
        assertThat(reloaded.getClosedAt()).isNull();
    }

    @Test
    void recordClosed_unknownTask_isNotFound() {
        assertThat(issueCloser.recordClosed(UUID.randomUUID(), 1)).isEqualTo(RecordOutcome.NOT_FOUND);
    }

    /** The orchestrator decodes this input by its Go JSON tags; a renamed field arrives as zero. */
    @Test
    void workflowInput_serializesToTheGoJsonTags() throws Exception {
        CloseTaskIssueWorkflowInput input =
                new CloseTaskIssueWorkflowInput(UUID.randomUUID(), UUID.randomUUID(), "acme/repo", 7, "worker-q");

        Payload payload =
                DefaultDataConverter.newDefaultInstance().toPayload(input).orElseThrow();
        JsonNode json = new ObjectMapper().readTree(payload.getData().toStringUtf8());

        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("taskId", "runId", "ownerRepo", "issueNumber", "workerTaskQueue");
        assertThat(json.get("taskId").asText()).isEqualTo(input.taskId().toString());
        assertThat(json.get("issueNumber").asInt()).isEqualTo(7);
    }

    private CloseTaskIssueWorkflowInput startedInput() {
        ArgumentCaptor<Object> input = ArgumentCaptor.forClass(Object.class);
        verify(issueCloseStub, times(1)).start(input.capture());
        assertThat(input.getValue()).isInstanceOf(CloseTaskIssueWorkflowInput.class);
        return (CloseTaskIssueWorkflowInput) input.getValue();
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

    private WorkflowRun finishedRunFor(UUID taskId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(graphTemplateId);
        run.setTaskId(taskId);
        run.setStatus(WorkflowRunStatus.completed);
        run.setExternalRunId("choruskube-run-" + UUID.randomUUID());
        return runRepo.save(run);
    }

    private void link(UUID taskId, int issueNumber, GithubIssueState state) {
        TaskGithubIssue issue = new TaskGithubIssue();
        issue.setTaskId(taskId);
        issue.setGitRepoId(repo.getId());
        issue.setIssueNumber(issueNumber);
        issue.setIssueUrl(repo.getUrl() + "/issues/" + issueNumber);
        issue.setState(state);
        taskGithubIssueRepo.save(issue);
    }

    private GitRepo makeRepo() {
        String url = "https://github.com/acme/task-issue-close-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo saved = gitRepoRepo.save(r);
        cleaner.trackSoftwareProject(saved.getId());
        return saved;
    }
}
