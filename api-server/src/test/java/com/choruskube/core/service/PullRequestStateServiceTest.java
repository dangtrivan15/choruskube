package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PullRequestStateServiceTest {

    @Mock
    private RunPullRequestRepository prRepo;

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private GitHubAppService gitHubAppService;

    @Mock
    private GitHubCredentialResolver credentialResolver;

    @Mock
    private TaskService taskService;

    @Mock
    private AutopilotSafetyValve safetyValve;

    @Captor
    private ArgumentCaptor<String> reason;

    private final UUID runId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID gitRepoId = UUID.randomUUID();

    @Test
    void refreshBatch_mergedPr_persistsMergedAtAndClosesTask() {
        RunPullRequest pr = pr(7);
        stubBatch(pr);
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("closed", Instant.parse("2026-08-16T10:00:00Z")));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));

        int merged = newService().refreshBatch(10);

        assertThat(merged).isEqualTo(1);
        assertThat(pr.getMergedAt()).isEqualTo(Instant.parse("2026-08-16T10:00:00Z"));
        assertThat(pr.getStateCheckedAt()).isNotNull();
        verify(taskService).closeForMergedPullRequests(taskId);
    }

    @Test
    void refreshBatch_openPr_recordsCheckAndDoesNotCloseTask() {
        RunPullRequest pr = pr(7);
        stubBatch(pr);
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("open", null));

        int merged = newService().refreshBatch(10);

        assertThat(merged).isZero();
        assertThat(pr.getMergedAt()).isNull();
        assertThat(pr.getStateCheckedAt()).isNotNull();
        verify(taskService, never()).closeForMergedPullRequests(any());
    }

    @Test
    void refreshBatch_runStillHasAnotherUnmergedPr_doesNotCloseTask() {
        RunPullRequest merged = pr(7);
        RunPullRequest other = pr(8);
        stubBatch(merged);
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("closed", Instant.now()));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(merged, other));

        newService().refreshBatch(10);

        verify(taskService, never()).closeForMergedPullRequests(any());
    }

    // -----------------------------------------------------------------------------------
    // The strictness rule: if we can no longer tell what is merged, stop automating
    // -----------------------------------------------------------------------------------

    /**
     * A revoked credential is the case this exists for. Nothing here fails loudly — the PR row is
     * untouched, the reconciler logs and moves on — so without the valve the Autopilot would keep
     * dispatching Tasks whose predecessors it can no longer see finish.
     */
    @Test
    void refreshBatch_gitHubReturns401_disengagesNamingTheRepository() {
        stubBatch(pr(42));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(401, "org/backend-api", 42));

        int merged = newService().refreshBatch(10);

        assertThat(merged).isZero();
        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(gitRepoId), reason.capture());
        assertThat(reason.getValue())
                .as("a human reading the panel must learn which repository and what went wrong")
                .contains("401")
                .contains("org/backend-api#42")
                .contains("credential");
    }

    @Test
    void refreshBatch_gitHubReturns404_disengages() {
        // A deleted or renamed repository, or one the credential can no longer see. Waiting does
        // not fix any of them, and every Task with a PR there is frozen until someone looks.
        stubBatch(pr(42));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(404, "org/backend-api", 42));

        newService().refreshBatch(10);

        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(gitRepoId), reason.capture());
        assertThat(reason.getValue()).contains("404").contains("org/backend-api#42");
    }

    @Test
    void refreshBatch_gitHubReturns403_disengages() {
        stubBatch(pr(42));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(403, "org/backend-api", 42));

        newService().refreshBatch(10);

        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(gitRepoId), reason.capture());
        assertThat(reason.getValue()).contains("403").contains("org/backend-api#42");
    }

    /**
     * The other half of the rule, and the half that keeps the feature usable: GitHub having a bad
     * minute must never stop the Autopilot, because a two-minute retry loop recovers from it
     * without anyone's help.
     */
    @Test
    void refreshBatch_gitHubReturns503_doesNotDisengageAndTheRowIsRetriedNextTick() {
        RunPullRequest pr = pr(42);
        stubBatch(pr);
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(503, "org/backend-api", 42))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("closed", Instant.parse("2026-08-16T10:00:00Z")));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        PullRequestStateService service = newService();

        assertThat(service.refreshBatch(10)).isZero();
        assertThat(pr.getStateCheckedAt())
                .as("nothing is written on a failure, so the row keeps its place at the front of the batch")
                .isNull();

        assertThat(service.refreshBatch(10))
                .as("the very next tick picks the same row up and finds it merged")
                .isEqualTo(1);
        verifyNoInteractions(safetyValve);
        verify(taskService).closeForMergedPullRequests(taskId);
    }

    /**
     * The rule's own direction, applied to the statuses nobody enumerated.
     *
     * <p>This used to be {@code default -> null}: every status not named was transient, which is the
     * unsafe side of the very trade the javadoc above it argues for. 410 Gone and 451 are as
     * permanent as a 404 and produced a two-minute retry loop forever while the Autopilot kept
     * dispatching against Tasks that could never close. A 3xx was transient too, and this client
     * does not follow redirects, so it would never have resolved one.
     */
    @ParameterizedTest
    @ValueSource(ints = {301, 410, 422, 451})
    void refreshBatch_gitHubReturnsAPermanentStatus_disengages(int status) {
        stubBatch(pr(42));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(status, "org/backend-api", 42));

        newService().refreshBatch(10);

        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(gitRepoId), reason.capture());
        assertThat(reason.getValue()).contains(String.valueOf(status)).contains("org/backend-api#42");
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504})
    void refreshBatch_gitHubReturnsAServerError_doesNotDisengage(int status) {
        // The closed list of what a retry loop fixes on its own. Widening the persistent side must
        // not narrow this one, or a bad minute at GitHub stops the Autopilot.
        stubBatch(pr(42));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(status, "org/backend-api", 42));

        newService().refreshBatch(10);

        verifyNoInteractions(safetyValve);
    }

    @Test
    void refreshBatch_gitHubReturns429_doesNotDisengage() {
        stubBatch(pr(42));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(429, "org/backend-api", 42));

        newService().refreshBatch(10);

        verifyNoInteractions(safetyValve);
    }

    @Test
    void refreshBatch_networkFailureWithNoStatus_doesNotDisengage() {
        // What fetchPullRequest raises for a timeout or a reset connection: a plain RuntimeException
        // with no status, because there was no response to take one from. Transient by nature.
        stubBatch(pr(42));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Failed to read org/backend-api#42: connection reset"));

        newService().refreshBatch(10);

        verifyNoInteractions(safetyValve);
    }

    @Test
    void refreshBatch_credentialResolutionFails_disengagesAndSkipsTheRowWithoutThrowing() {
        stubBatch(pr(7));
        stubRepoAndRun();
        when(credentialResolver.getTokenForRun(runId))
                .thenThrow(new IllegalStateException("No GitHub credential configured"));

        int merged = newService().refreshBatch(10);

        assertThat(merged).isZero();
        verify(taskService, never()).closeForMergedPullRequests(any());
        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(gitRepoId), reason.capture());
        assertThat(reason.getValue()).contains("org/backend-api").contains("credential");
    }

    /**
     * A failure inside {@code refreshOne} that has nothing to do with GitHub must not be read as
     * one. The resolver's {@link IllegalStateException} is narrowed at its own call site precisely
     * so that this one, thrown a few lines later, still classifies as transient.
     */
    @Test
    void refreshBatch_unrelatedIllegalStateException_doesNotDisengage() {
        stubBatch(pr(7));
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("open", null));
        when(prRepo.save(any(RunPullRequest.class))).thenThrow(new IllegalStateException("row is detached"));

        newService().refreshBatch(10);

        verifyNoInteractions(safetyValve);
    }

    @Test
    void refreshBatch_manyRowsBehindOneRevokedCredential_disengagesOnceAndStillChecksTheRest() {
        RunPullRequest first = pr(1);
        RunPullRequest second = pr(2);
        RunPullRequest healthy = pr(3);
        stubBatch(first, second, healthy);
        stubRepoAndRun();
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenThrow(new GitHubApiException(401, "org/backend-api", 1))
                .thenThrow(new GitHubApiException(401, "org/backend-api", 2))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("closed", Instant.parse("2026-08-16T10:00:00Z")));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(healthy));

        int merged = newService().refreshBatch(10);

        verify(safetyValve, times(1)).disengageForExternalFailure(eq("git_repo"), eq(gitRepoId), reason.capture());
        assertThat(reason.getValue())
                .as("one fault with one remedy — the first row's reason is the one a human needs")
                .contains("org/backend-api#1");
        assertThat(merged)
                .as("a fault on one repository says nothing about another, so the batch runs to the end")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------------------------
    // One stop per owning scope
    // -----------------------------------------------------------------------------------

    /**
     * The unmerged scan is installation-wide, so a batch is only single-scoped by accident of core
     * having one organisation. Downstream it spans them, and "disengage once per batch" would stop
     * whichever Autopilot owned the first failing repository — silencing one organisation over
     * another's revoked credential, which is the cross-organisation defect the seam removes.
     *
     * <p>The repository is what names the scope, so the reasons are keyed on it. Two repositories
     * sharing a scope resolve to the same Autopilot and the statement's {@code engaged} guard makes
     * the second call a no-op.
     */
    @Test
    void refreshBatch_twoRepositoriesFailing_stopsEachOwningScopeWithItsOwnReason() {
        UUID otherRepoId = UUID.randomUUID();
        stubBatch(pr(1), pr(2, otherRepoId));
        stubRepoAndRun();
        stubRepo(otherRepoId, "https://github.com/org/frontend-web.git");
        when(gitHubAppService.fetchPullRequest(anyString(), eq("org/backend-api"), anyInt()))
                .thenThrow(new GitHubApiException(401, "org/backend-api", 1));
        when(gitHubAppService.fetchPullRequest(anyString(), eq("org/frontend-web"), anyInt()))
                .thenThrow(new GitHubApiException(404, "org/frontend-web", 2));

        newService().refreshBatch(10);

        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(gitRepoId), contains("401"));
        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(otherRepoId), contains("404"));
    }

    @Test
    void refreshBatch_oneScopesStopThrowing_stillStopsTheOther() {
        // The scopes in a batch are independent. The reconciler's catch is a batch-wide boundary,
        // so without containment here the first unresolvable scope would take the stop away from
        // every scope behind it.
        UUID otherRepoId = UUID.randomUUID();
        stubBatch(pr(1), pr(2, otherRepoId));
        stubRepoAndRun();
        stubRepo(otherRepoId, "https://github.com/org/frontend-web.git");
        when(gitHubAppService.fetchPullRequest(anyString(), eq("org/backend-api"), anyInt()))
                .thenThrow(new GitHubApiException(401, "org/backend-api", 1));
        when(gitHubAppService.fetchPullRequest(anyString(), eq("org/frontend-web"), anyInt()))
                .thenThrow(new GitHubApiException(404, "org/frontend-web", 2));
        doThrow(new IllegalStateException("no ownership scope for that repository"))
                .when(safetyValve)
                .disengageForExternalFailure(anyString(), eq(gitRepoId), anyString());

        newService().refreshBatch(10);

        verify(safetyValve).disengageForExternalFailure(eq("git_repo"), eq(otherRepoId), contains("404"));
    }

    /**
     * A stop that fails must not strand pull requests that genuinely merged.
     *
     * <p>Closing is the record of work that finished; disengaging is the reaction to work that could
     * not be observed. Running the stops first — as this did — meant one failed scope resolution
     * threw out of the batch before a single Task was closed, freezing exactly the graph the valve
     * exists to protect.
     */
    @Test
    void refreshBatch_everyStopThrowing_stillClosesTheTasksThatMerged() {
        UUID otherRepoId = UUID.randomUUID();
        RunPullRequest merged = pr(2, otherRepoId);
        stubBatch(pr(1), merged);
        stubRepoAndRun();
        stubRepo(otherRepoId, "https://github.com/org/frontend-web.git");
        when(gitHubAppService.fetchPullRequest(anyString(), eq("org/backend-api"), anyInt()))
                .thenThrow(new GitHubApiException(401, "org/backend-api", 1));
        when(gitHubAppService.fetchPullRequest(anyString(), eq("org/frontend-web"), anyInt()))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("closed", Instant.parse("2026-08-16T10:00:00Z")));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(merged));
        doThrow(new IllegalStateException("no ownership scope for that repository"))
                .when(safetyValve)
                .disengageForExternalFailure(anyString(), any(), anyString());

        int newlyMerged = newService().refreshBatch(10);

        assertThat(newlyMerged).isEqualTo(1);
        verify(taskService).closeForMergedPullRequests(taskId);
    }

    @Test
    void refreshBatch_runHasNoTask_skipsClosure() {
        RunPullRequest pr = pr(7);
        stubBatch(pr);
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/org/backend-api.git");
        when(gitRepoRepo.findById(gitRepoId)).thenReturn(Optional.of(repo));
        WorkflowRun run = new WorkflowRun();
        run.setTaskId(null);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequest(anyString(), anyString(), anyInt()))
                .thenReturn(new GitHubAppService.PullRequestSnapshot("closed", Instant.now()));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));

        newService().refreshBatch(10);

        verify(taskService, never()).closeForMergedPullRequests(any());
    }

    private RunPullRequest pr(int number) {
        return pr(number, gitRepoId);
    }

    private RunPullRequest pr(int number, UUID repoId) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(repoId);
        pr.setPrUrl("https://github.com/org/backend-api/pull/" + number);
        pr.setPrNumber(number);
        return pr;
    }

    /** A second repository, so a batch can span more than one owning scope. */
    private void stubRepo(UUID repoId, String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    }

    private void stubBatch(RunPullRequest... prs) {
        when(prRepo.findUnmergedBatch(any(Pageable.class))).thenReturn(List.of(prs));
        when(prRepo.save(any(RunPullRequest.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void stubRepoAndRun() {
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/org/backend-api.git");
        when(gitRepoRepo.findById(gitRepoId)).thenReturn(Optional.of(repo));
        WorkflowRun run = new WorkflowRun();
        run.setTaskId(taskId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
    }

    private PullRequestStateService newService() {
        return new PullRequestStateService(
                prRepo, runRepo, gitRepoRepo, gitHubAppService, credentialResolver, taskService, safetyValve);
    }
}
