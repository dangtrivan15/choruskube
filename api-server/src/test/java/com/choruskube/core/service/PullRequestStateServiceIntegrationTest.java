package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.model.Autopilot;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * The strictness rule end to end: a GitHub read that cannot be fixed by waiting stops the Autopilot,
 * against real Postgres and the real {@link AutopilotService}.
 *
 * <p>What this shows that {@code PullRequestStateServiceTest}'s mocks cannot. The two services are
 * genuinely wired — a dependency cycle between them would fail this context rather than production
 * — and the disengage really is one guarded statement against a real row, so "already disengaged is
 * a no-op" is a property of the SQL rather than of a stub.
 *
 * <p>Only the two outbound seams are mocked, and both have to be: {@code GitHubAppService} would
 * otherwise call github.com, and {@code GitHubCredentialResolver} reads {@code GITHUB_PAT} straight
 * from the environment, so on a developer's machine it answers differently than in CI. That the real
 * resolver throws when nothing is configured — the assumption the credential case rests on — is
 * pinned by {@code EnvGitHubCredentialResolverTest} instead.
 *
 * <p>{@code @Transactional} and therefore rolled back: nothing here commits, because every write on
 * this path joins the caller's transaction — the reconciler that normally drives {@code
 * refreshBatch} is not itself transactional, but neither does it need its own connection. No
 * {@code CommittedFixtureCleaner}, for the same reason.
 */
@Transactional
public class PullRequestStateServiceIntegrationTest extends BaseTest {

    private static final int PR_NUMBER = 42;

    /** Wide enough that rows other test classes committed to the shared database cannot crowd this one out. */
    private static final int WIDE_BATCH = 1000;

    @Autowired
    private PullRequestStateService pullRequestStateService;

    @Autowired
    private AutopilotService autopilotService;

    @Autowired
    private AutopilotRepository autopilotRepo;

    @Autowired
    private RunPullRequestRepository prRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    /** Mocked because a real HTTP call to github.com has no place in this suite. */
    @MockitoBean
    private GitHubAppService gitHubAppService;

    /** Mocked because the real one reads {@code GITHUB_PAT} from the developer's environment. */
    @MockitoBean
    private GitHubCredentialResolver credentialResolver;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private StoryRepository storyRepo;

    @Autowired
    private TaskRepository taskRepo;

    private String ownerRepo;
    private UUID gitRepoId;
    private UUID graphTemplateId;

    @BeforeEach
    void registerAPullRequestToRefresh() {
        when(credentialResolver.getTokenForRun(any())).thenReturn("t0ken");

        GraphTemplate template = new GraphTemplate();
        template.setName("PR Strictness Template");
        template.setGraphId("pr-strictness-template-" + UUID.randomUUID());
        template.setVersion(1);
        graphTemplateId = graphTemplateRepo.save(template).getId();

        ownerRepo = "org/backend-api-" + UUID.randomUUID().toString().substring(0, 8);
        GitRepo gitRepo = new GitRepo();
        gitRepo.setName(ownerRepo);
        gitRepo.setUrl("https://github.com/" + ownerRepo + ".git");
        gitRepoId = gitRepoRepo.save(gitRepo).getId();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(graphTemplateId);
        UUID runId = runRepo.save(run).getId();

        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl("https://github.com/" + ownerRepo + "/pull/" + PR_NUMBER);
        pr.setPrNumber(PR_NUMBER);
        prRepo.saveAndFlush(pr);
    }

    @Test
    void aRevokedCredential_disengagesTheRealAutopilotRow() {
        UUID autopilotId = engage();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(401, ownerRepo, PR_NUMBER));

        pullRequestStateService.refreshBatch(10);

        Autopilot after = autopilotRepo.findById(autopilotId).orElseThrow();
        assertThat(after.isEngaged()).isFalse();
        assertThat(after.getDisengagedReason())
                .contains("401")
                .contains(ownerRepo + "#" + PR_NUMBER)
                .contains("credential");
        assertThat(after.getConsecutiveFailures())
                .as("the run-failure breaker is a different mechanism and must not have moved")
                .isZero();
    }

    /**
     * The fresh-installation shape: nothing configured, so the resolver throws exactly what {@code
     * EnvGitHubCredentialResolver} throws in that case. It has to classify as persistent — not
     * having a credential at all is not a state to keep automating through.
     */
    @Test
    void noCredentialConfiguredAtAll_disengages() {
        UUID autopilotId = engage();
        when(credentialResolver.getTokenForRun(any()))
                .thenThrow(new IllegalStateException(
                        "No GitHub credential configured (set GITHUB_PAT or the github.app.* env)"));

        pullRequestStateService.refreshBatch(10);

        Autopilot after = autopilotRepo.findById(autopilotId).orElseThrow();
        assertThat(after.isEngaged()).isFalse();
        assertThat(after.getDisengagedReason()).contains(ownerRepo).contains("credential");
    }

    @Test
    void anOutage_leavesTheAutopilotEngagedAndTheRowToBeRetried() {
        UUID autopilotId = engage();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(503, ownerRepo, PR_NUMBER));

        pullRequestStateService.refreshBatch(10);

        Autopilot after = autopilotRepo.findById(autopilotId).orElseThrow();
        assertThat(after.isEngaged())
                .as("GitHub having a bad minute must never stop the Autopilot")
                .isTrue();
        assertThat(after.getDisengagedReason()).isNull();
        // Since V17 the row yields its place instead of holding the front of the queue: it is not
        // due right now, but it is due one backoff base from now and the scan will read it then.
        // Asserted against a future clock rather than "is not empty", because "still selected
        // immediately" is exactly the starving behaviour that was removed.
        assertThat(prRepo.findUnmergedBatch(Instant.now(), PageRequest.of(0, 10)))
                .as("the failed row has stepped aside for this tick")
                .isEmpty();
        assertThat(prRepo.findUnmergedBatch(Instant.now().plus(Duration.ofHours(1)), PageRequest.of(0, 10)))
                .as("...but it is retried, not abandoned")
                .isNotEmpty();
    }

    @Test
    void aPersistentFailureWithNoAutopilotConfigured_createsNoRow() {
        long before = autopilotRepo.count();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(404, ownerRepo, PR_NUMBER));

        pullRequestStateService.refreshBatch(10);

        assertThat(autopilotRepo.count())
                .as("an installation that never opted in must not be handed a disengaged Autopilot")
                .isEqualTo(before);
    }

    /**
     * A human merges before approving the run's last gate: the tick that sees the merge finds the
     * run still active, so closing is refused. Nothing re-reads a merged row, so without a later
     * check the Task would stay in progress for good.
     */
    @Test
    void aRunThatFinishesAfterItsLastMergeWasSeen_closesItsTaskOnALaterTick() {
        UUID taskId = inProgressTask();
        WorkflowRun run = runFor(taskId, WorkflowRunStatus.awaiting_human);
        registerPullRequest(run.getId(), 43);
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("closed", Instant.now()));

        pullRequestStateService.refreshBatch(WIDE_BATCH);
        assertThat(taskRepo.findById(taskId).orElseThrow().getStatus())
                .as("the run is still active when the merge is seen")
                .isEqualTo(WorkItemStatus.in_progress);

        run.setStatus(WorkflowRunStatus.completed);
        runRepo.saveAndFlush(run);
        pullRequestStateService.refreshBatch(WIDE_BATCH);

        assertThat(taskRepo.findById(taskId).orElseThrow().getStatus()).isEqualTo(WorkItemStatus.done);
    }

    @Test
    void aFinishedRunThatOpenedNoPullRequest_leavesItsTaskOpen() {
        UUID taskId = inProgressTask();
        runFor(taskId, WorkflowRunStatus.completed);

        pullRequestStateService.refreshBatch(10);

        assertThat(taskRepo.findById(taskId).orElseThrow().getStatus())
                .as("nothing was merged, so nothing says the work landed")
                .isEqualTo(WorkItemStatus.in_progress);
    }

    private UUID inProgressTask() {
        Epic epic = new Epic();
        epic.setTitle("PR closure epic");
        epic.setDescription("d");
        epic.setSoftwareProjectId(gitRepoId);
        Story story = new Story();
        story.setEpicId(epicRepo.saveAndFlush(epic).getId());
        story.setTitle("PR closure story");
        story.setDescription("d");
        Task task = new Task();
        task.setStoryId(storyRepo.saveAndFlush(story).getId());
        task.setSoftwareProjectId(gitRepoId);
        task.setTitle("PR closure task");
        task.setDescription("d");
        task.setStatus(WorkItemStatus.in_progress);
        return taskRepo.saveAndFlush(task).getId();
    }

    private WorkflowRun runFor(UUID taskId, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(graphTemplateId);
        run.setTaskId(taskId);
        run.setStatus(status);
        return runRepo.saveAndFlush(run);
    }

    private void registerPullRequest(UUID runId, int number) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl("https://github.com/" + ownerRepo + "/pull/" + number);
        pr.setPrNumber(number);
        prRepo.saveAndFlush(pr);
    }

    /** Engages through the real path, which is also what creates the row. */
    private UUID engage() {
        autopilotService.engage();
        Autopilot autopilot = autopilotRepo.findAll().getFirst();
        assertThat(autopilot.isEngaged()).isTrue();
        return autopilot.getId();
    }
}
