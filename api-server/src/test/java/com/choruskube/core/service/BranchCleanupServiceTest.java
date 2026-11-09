package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.BranchCleanupPlanResponse;
import com.choruskube.core.dto.GraphRuntimeSnapshotResponse;
import com.choruskube.core.dto.RunStatusResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BranchCleanupService#plan}, the read-only list of a completed run's repos
 * the orchestrator carries to the run's Worker. Every lookup ({@link InternalRunService}, {@link
 * GitRepoRepository}) is mocked.
 */
@ExtendWith(MockitoExtension.class)
class BranchCleanupServiceTest {

    @Mock
    private InternalRunService internalRunService;

    @Mock
    private GitRepoRepository gitRepoRepo;

    private BranchCleanupService service;

    @BeforeEach
    void setUp() {
        service = new BranchCleanupService(internalRunService, gitRepoRepo);
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
    void plan_listsEachRunRepoWithOwnerRepoAndDefaultBranch() {
        UUID runId = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();
        GitRepo repo = repo(repoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        when(internalRunService.getRunStatus(runId)).thenReturn(new RunStatusResponse("completed"));
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(repo));
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));

        BranchCleanupPlanResponse plan = service.plan(runId);

        assertThat(plan.repos()).singleElement().satisfies(r -> {
            assertThat(r.gitRepoId()).isEqualTo(repoId);
            assertThat(r.ownerRepo()).isEqualTo("org/backend-api");
            assertThat(r.defaultBranch()).isEqualTo("main");
        });
    }

    @Test
    void plan_skipsUnparseableMissingAndSluglessRepos() {
        UUID runId = UUID.randomUUID();
        UUID missingRepoId = UUID.randomUUID();
        UUID sluglessRepoId = UUID.randomUUID();
        UUID healthyRepoId = UUID.randomUUID();
        GitRepo missingRepo = repo(missingRepoId, "https://github.com/org/gone.git", "main", "org/gone");
        GitRepo sluglessRepo = repo(sluglessRepoId, "", "main", "slugless");
        GitRepo healthyRepo = repo(healthyRepoId, "https://github.com/org/web-ui.git", "main", "org/web-ui");
        List<GraphRuntimeSnapshotResponse.RuntimeRepo> runtimeRepos = List.of(
                new GraphRuntimeSnapshotResponse.RuntimeRepo("not-a-uuid", "https://x/y.git", "y", null, null),
                new GraphRuntimeSnapshotResponse.RuntimeRepo(
                        missingRepoId.toString(), missingRepo.getUrl(), missingRepo.getName(), null, null),
                new GraphRuntimeSnapshotResponse.RuntimeRepo(
                        sluglessRepoId.toString(), sluglessRepo.getUrl(), sluglessRepo.getName(), null, null),
                new GraphRuntimeSnapshotResponse.RuntimeRepo(
                        healthyRepoId.toString(), healthyRepo.getUrl(), healthyRepo.getName(), null, null));
        when(internalRunService.getRunStatus(runId)).thenReturn(new RunStatusResponse("completed"));
        when(internalRunService.getGraphRuntimeSnapshot(runId))
                .thenReturn(new GraphRuntimeSnapshotResponse(List.of(), List.of(), Map.of(), runtimeRepos, null));
        when(gitRepoRepo.findById(missingRepoId)).thenReturn(Optional.empty());
        when(gitRepoRepo.findById(sluglessRepoId)).thenReturn(Optional.of(sluglessRepo));
        when(gitRepoRepo.findById(healthyRepoId)).thenReturn(Optional.of(healthyRepo));

        BranchCleanupPlanResponse plan = service.plan(runId);

        assertThat(plan.repos()).singleElement().satisfies(r -> assertThat(r.gitRepoId())
                .isEqualTo(healthyRepoId));
    }

    @Test
    void plan_skipsARepoNotHostedOnGitHub() {
        UUID runId = UUID.randomUUID();
        UUID githubRepoId = UUID.randomUUID();
        UUID otherHostRepoId = UUID.randomUUID();
        GitRepo githubRepo = repo(githubRepoId, "https://github.com/org/backend-api.git", "main", "org/backend-api");
        GitRepo otherHostRepo = repo(otherHostRepoId, "https://example.invalid/org/other.git", "main", "org/other");
        when(internalRunService.getRunStatus(runId)).thenReturn(new RunStatusResponse("completed"));
        when(internalRunService.getGraphRuntimeSnapshot(runId)).thenReturn(snapshotOf(githubRepo, otherHostRepo));
        when(gitRepoRepo.findById(githubRepoId)).thenReturn(Optional.of(githubRepo));
        when(gitRepoRepo.findById(otherHostRepoId)).thenReturn(Optional.of(otherHostRepo));

        BranchCleanupPlanResponse plan = service.plan(runId);

        assertThat(plan.repos()).singleElement().satisfies(r -> assertThat(r.gitRepoId())
                .isEqualTo(githubRepoId));
    }

    @ParameterizedTest
    @EnumSource(value = WorkflowRunStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "completed")
    void plan_refusesARunThatIsNotCompleted(WorkflowRunStatus status) {
        UUID runId = UUID.randomUUID();
        when(internalRunService.getRunStatus(runId)).thenReturn(new RunStatusResponse(status.name()));

        assertThatThrownBy(() -> service.plan(runId)).isInstanceOf(ConflictException.class);
        verify(internalRunService, never()).getGraphRuntimeSnapshot(any());
    }

    @Test
    void plan_unknownRunIsNotFound() {
        UUID runId = UUID.randomUUID();
        when(internalRunService.getRunStatus(runId))
                .thenThrow(new NotFoundException("Workflow run not found: " + runId));

        assertThatThrownBy(() -> service.plan(runId)).isInstanceOf(NotFoundException.class);
    }
}
