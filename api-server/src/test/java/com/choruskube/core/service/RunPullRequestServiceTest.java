package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CreateRunPullRequestRequest;
import com.choruskube.core.dto.RunPullRequestResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class RunPullRequestServiceTest {

    @Mock
    private RunPullRequestRepository prRepo;

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    private RunPullRequestService service;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID NODE_EXEC_ID = UUID.randomUUID();
    private static final UUID GIT_REPO_ID_1 = UUID.randomUUID();
    private static final UUID GIT_REPO_ID_2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunPullRequestService(
                prRepo, runRepo, gitRepoRepo, execRepo, eventPublisher, mock(ApplicationEventPublisher.class));
    }

    @Test
    void createPullRequest_withValidRunAndRepo_succeeds() {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(RUN_ID);
        when(execRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(exec));

        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));

        GitRepo gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/org/backend-api");
        when(gitRepoRepo.findById(GIT_REPO_ID_1)).thenReturn(Optional.of(gitRepo));

        when(prRepo.findByWorkflowRunIdAndPrUrl(eq(RUN_ID), anyString())).thenReturn(Optional.empty());
        when(prRepo.save(any(RunPullRequest.class))).thenAnswer(inv -> {
            RunPullRequest pr = inv.getArgument(0);
            // Simulate ID generation
            try {
                var idField = RunPullRequest.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(pr, UUID.randomUUID());
            } catch (Exception e) {
                // ignore
            }
            return pr;
        });

        CreateRunPullRequestRequest req = new CreateRunPullRequestRequest(
                GIT_REPO_ID_1, "https://github.com/org/backend-api/pull/42", 42, "feat: add login", "backend-api");

        RunPullRequestResponse response = service.createPullRequest(RUN_ID, NODE_EXEC_ID, req);

        assertThat(response).isNotNull();
        assertThat(response.prUrl()).isEqualTo("https://github.com/org/backend-api/pull/42");
        assertThat(response.prNumber()).isEqualTo(42);
        assertThat(response.title()).isEqualTo("feat: add login");
        assertThat(response.repoName()).isEqualTo("backend-api");
        assertThat(response.repoUrl()).isEqualTo("https://github.com/org/backend-api");

        // Verify WebSocket event published
        verify(eventPublisher).publishPullRequestCreated(RUN_ID);
    }

    @Test
    void createPullRequest_reRegisterSameUrl_updatesExistingRowAndKeepsSingleRecord() {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(RUN_ID);
        when(execRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(exec));

        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));

        GitRepo gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/org/backend-api");
        when(gitRepoRepo.findById(GIT_REPO_ID_1)).thenReturn(Optional.of(gitRepo));

        UUID existingId = UUID.randomUUID();
        UUID priorNodeExecId = UUID.randomUUID();
        String prUrl = "https://github.com/org/backend-api/pull/42";

        // Pre-existing row from a prior register-pr call (same run, same URL).
        RunPullRequest existing = new RunPullRequest();
        try {
            var idField = RunPullRequest.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(existing, existingId);
        } catch (Exception e) {
            // ignore
        }
        existing.setWorkflowRunId(RUN_ID);
        existing.setGitRepoId(GIT_REPO_ID_1);
        existing.setNodeExecutionId(priorNodeExecId);
        existing.setPrUrl(prUrl);
        existing.setPrNumber(42);
        existing.setTitle("feat: add login (old title)");
        existing.setRepoName("backend-api");

        when(prRepo.findByWorkflowRunIdAndPrUrl(RUN_ID, prUrl)).thenReturn(Optional.of(existing));
        when(prRepo.save(any(RunPullRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        // Second register-pr call with refreshed metadata (e.g. title was edited mid-run).
        CreateRunPullRequestRequest req =
                new CreateRunPullRequestRequest(GIT_REPO_ID_1, prUrl, 42, "feat: add login (refreshed)", "backend-api");

        RunPullRequestResponse response = service.createPullRequest(RUN_ID, NODE_EXEC_ID, req);

        // Single row, original id preserved, new metadata applied.
        assertThat(response.id()).isEqualTo(existingId);
        assertThat(response.title()).isEqualTo("feat: add login (refreshed)");
        assertThat(response.nodeExecutionId()).isEqualTo(NODE_EXEC_ID);
        // Save called exactly once and on the existing entity (no insert).
        verify(prRepo).save(existing);
        // Event publishes on every register call so STOMP consumers always see fresh data.
        verify(eventPublisher).publishPullRequestCreated(RUN_ID);
    }

    @Test
    void createPullRequest_withInvalidRun_throwsNotFoundException() {
        // The execution must still resolve to RUN_ID so the run-scoping guard passes and the test
        // reaches the run lookup it's actually asserting on.
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(RUN_ID);
        when(execRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(exec));

        when(runRepo.findById(RUN_ID)).thenReturn(Optional.empty());

        CreateRunPullRequestRequest req = new CreateRunPullRequestRequest(
                GIT_REPO_ID_1, "https://github.com/org/repo/pull/1", 1, "title", "repo");

        assertThatThrownBy(() -> service.createPullRequest(RUN_ID, NODE_EXEC_ID, req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createPullRequest_withInvalidRepo_throwsNotFoundException() {
        // The execution must still resolve to RUN_ID so the run-scoping guard passes and the test
        // reaches the git repo lookup it's actually asserting on.
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(RUN_ID);
        when(execRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(exec));

        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(gitRepoRepo.findById(GIT_REPO_ID_1)).thenReturn(Optional.empty());

        CreateRunPullRequestRequest req = new CreateRunPullRequestRequest(
                GIT_REPO_ID_1, "https://github.com/org/repo/pull/1", 1, "title", "repo");

        assertThatThrownBy(() -> service.createPullRequest(RUN_ID, NODE_EXEC_ID, req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getPullRequests_withMultiplePRs_batchLoadsRepoUrls() {
        RunPullRequest pr1 = createPrEntity(GIT_REPO_ID_1, "https://github.com/org/backend-api/pull/1", "backend-api");
        RunPullRequest pr2 =
                createPrEntity(GIT_REPO_ID_2, "https://github.com/org/frontend-app/pull/2", "frontend-app");
        when(prRepo.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(pr1, pr2));

        GitRepo repo1 = new GitRepo();
        repo1.setId(GIT_REPO_ID_1);
        repo1.setUrl("https://github.com/org/backend-api");

        GitRepo repo2 = new GitRepo();
        repo2.setId(GIT_REPO_ID_2);
        repo2.setUrl("https://github.com/org/frontend-app");

        when(gitRepoRepo.findAllById(anyIterable())).thenReturn(List.of(repo1, repo2));

        List<RunPullRequestResponse> responses = service.getPullRequests(RUN_ID);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).repoUrl()).isEqualTo("https://github.com/org/backend-api");
        assertThat(responses.get(1).repoUrl()).isEqualTo("https://github.com/org/frontend-app");

        // Verify batch-load was used (single findAllById call) instead of N individual findById calls
        verify(gitRepoRepo).findAllById(anyIterable());
        verify(gitRepoRepo, never()).findById(any(UUID.class));
    }

    @Test
    void getPullRequests_withNoPRs_returnsEmptyList() {
        when(prRepo.findByWorkflowRunId(RUN_ID)).thenReturn(List.of());

        List<RunPullRequestResponse> responses = service.getPullRequests(RUN_ID);

        assertThat(responses).isEmpty();
        // Should not call gitRepoRepo at all when no PRs exist
        verifyNoInteractions(gitRepoRepo);
    }

    @Test
    void getPullRequests_withMissingRepo_returnsEmptyUrl() {
        RunPullRequest pr1 = createPrEntity(GIT_REPO_ID_1, "https://github.com/org/repo/pull/1", "repo");
        when(prRepo.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(pr1));

        // Repo not found in batch load — returns empty list
        when(gitRepoRepo.findAllById(anyIterable())).thenReturn(List.of());

        List<RunPullRequestResponse> responses = service.getPullRequests(RUN_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).repoUrl()).isEmpty();
    }

    // ── getPullRequestsForNodeExecution (cross-run leak guard) ──────────────

    @Test
    void getPullRequestsForNodeExecution_withOwnRun_returnsRunsPullRequests() {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(RUN_ID);
        when(execRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(exec));

        RunPullRequest pr1 = createPrEntity(GIT_REPO_ID_1, "https://github.com/org/backend-api/pull/1", "backend-api");
        when(prRepo.findByWorkflowRunId(RUN_ID)).thenReturn(List.of(pr1));

        GitRepo repo1 = new GitRepo();
        repo1.setId(GIT_REPO_ID_1);
        repo1.setUrl("https://github.com/org/backend-api");
        when(gitRepoRepo.findAllById(anyIterable())).thenReturn(List.of(repo1));

        List<RunPullRequestResponse> responses = service.getPullRequestsForNodeExecution(RUN_ID, NODE_EXEC_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).prUrl()).isEqualTo("https://github.com/org/backend-api/pull/1");
    }

    @Test
    void getPullRequestsForNodeExecution_withUnrelatedRunId_throwsNotFoundException() {
        // The node execution is real and belongs to RUN_ID, but the caller substitutes a
        // different, unrelated run's UUID in the path while presenting its own valid
        // nodeExecId — the exact leak vector this cross-check exists to close, since
        // InternalAuthFilter itself only validates the nodeExecId segment, never runId.
        UUID unrelatedRunId = UUID.randomUUID();
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(RUN_ID);
        when(execRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(exec));

        assertThatThrownBy(() -> service.getPullRequestsForNodeExecution(unrelatedRunId, NODE_EXEC_ID))
                .isInstanceOf(NotFoundException.class);

        // Must reject before ever touching the unrelated run's PR data.
        verifyNoInteractions(prRepo);
    }

    @Test
    void getPullRequestsForNodeExecution_withUnknownNodeExecId_throwsNotFoundException() {
        when(execRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPullRequestsForNodeExecution(RUN_ID, NODE_EXEC_ID))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(prRepo);
    }

    private RunPullRequest createPrEntity(UUID gitRepoId, String prUrl, String repoName) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(RUN_ID);
        pr.setGitRepoId(gitRepoId);
        pr.setNodeExecutionId(NODE_EXEC_ID);
        pr.setPrUrl(prUrl);
        pr.setPrNumber(1);
        pr.setTitle("PR title");
        pr.setRepoName(repoName);
        return pr;
    }
}
