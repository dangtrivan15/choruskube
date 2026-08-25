package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.BranchCleanupResponse;
import com.choruskube.core.dto.GraphRuntimeSnapshotResponse;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BranchCleanupService}, which best-effort deletes a completed run's
 * per-repo run branch when it is not ahead of that repo's default branch (Part 2 of the stale
 * run-branch cleanup feature). Every network dependency ({@link GitHubAppService}) and lookup
 * ({@link InternalRunService}, {@link GitRepoRepository}, {@link GitHubCredentialResolver}) is
 * mocked — the fail-safe contract under test is about how per-repo outcomes are classified and
 * isolated, not about real HTTP behavior (that lives in {@link GitHubAppServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class BranchCleanupServiceTest {

    @Mock
    private InternalRunService internalRunService;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private GitHubCredentialResolver credentialResolver;

    @Mock
    private GitHubAppService gitHubAppService;

    private BranchCleanupService service;

    @BeforeEach
    void setUp() {
        service = new BranchCleanupService(internalRunService, gitRepoRepo, credentialResolver, gitHubAppService);
        lenient().when(credentialResolver.getTokenForRun(any())).thenReturn("t0ken");
    }

    private static GitRepo repo(UUID id, String url, String defaultBranch, String name) {
        GitRepo repo = new GitRepo();
        repo.setId(id);
        repo.setUrl(url);
        repo.setDefaultBranch(defaultBranch);
        repo.setName(name);
        return repo;
    }

    private static GraphRuntimeSnapshotResponse snapshotOf(GitRepo... repos) {
        List<GraphRuntimeSnapshotResponse.RuntimeRepo> runtimeRepos = List.of(repos).stream()
                .map(r -> new GraphRuntimeSnapshotResponse.RuntimeRepo(
                        r.getId().toString(), r.getUrl(), r.getName(), null, null))
                .toList();
        return new GraphRuntimeSnapshotResponse(List.of(), List.of(), Map.of(), runtimeRepos, null);
    }

    @Test
    void branchName_derivedFromRunId() {
        UUID runId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        GitRepo repo = repo(repoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(repo));
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(gitHubAppService.compareCommits(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        BranchCleanupResponse response = service.cleanupBranches(runId);

        String expectedBranch = "choruskube-run-" + runId;
        assertThat(response.results()).singleElement().satisfies(r -> assertThat(r.branch())
                .isEqualTo(expectedBranch));
        verify(gitHubAppService).compareCommits("t0ken", "org/backend-api", "main", expectedBranch);
        verify(gitHubAppService).deleteRef("t0ken", "org/backend-api", "heads/" + expectedBranch);
    }

    @Test
    void repoAhead_isKeptAndNeverDeleted() {
        UUID runId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        GitRepo repo = repo(repoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(repo));
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(gitHubAppService.compareCommits(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(2);

        BranchCleanupResponse response = service.cleanupBranches(runId);

        assertThat(response.results()).singleElement().satisfies(r -> {
            assertThat(r.gitRepoId()).isEqualTo(repoId);
            assertThat(r.outcome()).isEqualTo("KEPT_AHEAD");
        });
        verify(gitHubAppService, never()).deleteRef(anyString(), anyString(), anyString());
    }

    @Test
    void repoAtParity_isDeleted() {
        UUID runId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        GitRepo repo = repo(repoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(repo));
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(gitHubAppService.compareCommits(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0);
        when(gitHubAppService.deleteRef(anyString(), anyString(), anyString())).thenReturn(true);

        BranchCleanupResponse response = service.cleanupBranches(runId);

        assertThat(response.results()).singleElement().satisfies(r -> {
            assertThat(r.gitRepoId()).isEqualTo(repoId);
            assertThat(r.outcome()).isEqualTo("DELETED");
        });
        verify(gitHubAppService).deleteRef(eq("t0ken"), eq("org/backend-api"), anyString());
    }

    @Test
    void compareCommitsNotFound_isReportedAsNotFound() {
        UUID runId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        GitRepo repo = repo(repoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(repo));
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(gitHubAppService.compareCommits(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new GitHubApiException(404, "org/backend-api", null));

        BranchCleanupResponse response = service.cleanupBranches(runId);

        assertThat(response.results()).singleElement().satisfies(r -> assertThat(r.outcome())
                .isEqualTo("NOT_FOUND"));
        verify(gitHubAppService, never()).deleteRef(anyString(), anyString(), anyString());
    }

    @Test
    void compareCommitsServerError_isSkippedButOtherReposStillProcess() {
        UUID runId = UUID.randomUUID();
        UUID failingRepoId = UUID.randomUUID();
        UUID healthyRepoId = UUID.randomUUID();
        GitRepo failingRepo = repo(failingRepoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        GitRepo healthyRepo = repo(healthyRepoId, "https://github.com/org/web-ui.git", "main", "org/web-ui");
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(failingRepo, healthyRepo));
        when(gitRepoRepo.findById(failingRepoId)).thenReturn(Optional.of(failingRepo));
        when(gitRepoRepo.findById(healthyRepoId)).thenReturn(Optional.of(healthyRepo));
        when(gitHubAppService.compareCommits(anyString(), eq("org/backend-api"), anyString(), anyString()))
                .thenThrow(new GitHubApiException(500, "org/backend-api", null));
        when(gitHubAppService.compareCommits(anyString(), eq("org/web-ui"), anyString(), anyString()))
                .thenReturn(0);

        BranchCleanupResponse response = service.cleanupBranches(runId);

        assertThat(response.results()).hasSize(2);
        assertThat(response.results())
                .filteredOn(r -> r.gitRepoId().equals(failingRepoId))
                .singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo("SKIPPED_ERROR"));
        assertThat(response.results())
                .filteredOn(r -> r.gitRepoId().equals(healthyRepoId))
                .singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo("DELETED"));
    }

    /**
     * {@link GitHubCredentialResolver#getTokenForRun} takes only a {@code runId}, not a repo, so a
     * genuinely repo-scoped credential failure can't be modeled directly — but the per-repo loop
     * calls it independently on every iteration, and this proves that isolation: the first repo's
     * resolution fails, the second repo's (a separate call to the same mock) succeeds and processes
     * normally, exactly as {@link #compareCommitsServerError_isSkippedButOtherReposStillProcess}
     * proves for a GitHub-side failure instead of a credential one.
     */
    @Test
    void credentialResolutionFailure_isSkippedButOtherReposStillProcess() {
        UUID runId = UUID.randomUUID();
        UUID failingRepoId = UUID.randomUUID();
        UUID healthyRepoId = UUID.randomUUID();
        GitRepo failingRepo = repo(failingRepoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        GitRepo healthyRepo = repo(healthyRepoId, "https://github.com/org/web-ui.git", "main", "org/web-ui");
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(failingRepo, healthyRepo));
        when(gitRepoRepo.findById(failingRepoId)).thenReturn(Optional.of(failingRepo));
        when(gitRepoRepo.findById(healthyRepoId)).thenReturn(Optional.of(healthyRepo));
        // Override the lenient default: the first call (failingRepo's iteration) throws, the
        // second (healthyRepo's iteration) succeeds.
        when(credentialResolver.getTokenForRun(runId))
                .thenThrow(new RuntimeException("no credential configured"))
                .thenReturn("t0ken");
        when(gitHubAppService.compareCommits(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        BranchCleanupResponse response = service.cleanupBranches(runId);

        assertThat(response.results()).hasSize(2);
        assertThat(response.results())
                .filteredOn(r -> r.gitRepoId().equals(failingRepoId))
                .singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo("SKIPPED_ERROR"));
        assertThat(response.results())
                .filteredOn(r -> r.gitRepoId().equals(healthyRepoId))
                .singleElement()
                .satisfies(r -> assertThat(r.outcome()).isEqualTo("DELETED"));
        verify(gitHubAppService, never()).compareCommits(anyString(), eq("org/backend-api"), anyString(), anyString());
    }

    @Test
    void emptyRepoSet_returnsEmptyResultList() {
        UUID runId = UUID.randomUUID();
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf());

        BranchCleanupResponse response = service.cleanupBranches(runId);

        assertThat(response.results()).isEmpty();
    }
}
