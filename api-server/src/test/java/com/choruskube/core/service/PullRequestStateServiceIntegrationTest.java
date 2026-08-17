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
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
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

    private String ownerRepo;

    @BeforeEach
    void registerAPullRequestToRefresh() {
        when(credentialResolver.getTokenForRun(any())).thenReturn("t0ken");

        GraphTemplate template = new GraphTemplate();
        template.setName("PR Strictness Template");
        template.setGraphId("pr-strictness-template-" + UUID.randomUUID());
        template.setVersion(1);
        UUID graphTemplateId = graphTemplateRepo.save(template).getId();

        ownerRepo = "org/backend-api-" + UUID.randomUUID().toString().substring(0, 8);
        GitRepo gitRepo = new GitRepo();
        gitRepo.setName(ownerRepo);
        gitRepo.setUrl("https://github.com/" + ownerRepo + ".git");
        UUID gitRepoId = gitRepoRepo.save(gitRepo).getId();

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
        assertThat(prRepo.findUnmergedBatch(PageRequest.of(0, 10)))
                .as("the row is untouched, so the next tick reads it again")
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

    /** Engages through the real path, which is also what creates the row. */
    private UUID engage() {
        autopilotService.engage();
        Autopilot autopilot = autopilotRepo.findAll().getFirst();
        assertThat(autopilot.isEngaged()).isTrue();
        return autopilot.getId();
    }
}
