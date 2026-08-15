package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.credential.GitHubCredentialResolver;
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

    @Test
    void refreshBatch_credentialResolutionFails_skipsRowWithoutThrowing() {
        stubBatch(pr(7));
        stubRepoAndRun();
        when(credentialResolver.getTokenForRun(runId))
                .thenThrow(new IllegalStateException("No GitHub credential configured"));

        int merged = newService().refreshBatch(10);

        assertThat(merged).isZero();
        verify(taskService, never()).closeForMergedPullRequests(any());
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
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl("https://github.com/org/backend-api/pull/" + number);
        pr.setPrNumber(number);
        return pr;
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
                prRepo, runRepo, gitRepoRepo, gitHubAppService, credentialResolver, taskService);
    }
}
