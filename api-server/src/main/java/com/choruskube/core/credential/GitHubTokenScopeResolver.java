package com.choruskube.core.credential;

import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.GraphSnapshotBuilder;
import com.choruskube.core.util.RepoNameUtil;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "what GitHub token scope does run R (or repository G) need" — the single definition
 * both mint paths narrow against: the control plane that brokers a mint applies it directly, and a
 * Worker holding its own App key fetches it over the scope route before minting.
 */
@Component
public class GitHubTokenScopeResolver {

    private final WorkflowRunRepository workflowRunRepository;
    private final GitRepoRepository gitRepoRepository;
    private final GraphSnapshotBuilder graphSnapshotBuilder;

    public GitHubTokenScopeResolver(
            WorkflowRunRepository workflowRunRepository,
            GitRepoRepository gitRepoRepository,
            GraphSnapshotBuilder graphSnapshotBuilder) {
        this.workflowRunRepository = workflowRunRepository;
        this.gitRepoRepository = gitRepoRepository;
        this.graphSnapshotBuilder = graphSnapshotBuilder;
    }

    /** The run's scope: every GitHub-hosted repository it spans, plus {@link GitHubTokenScope#RUN_PERMISSIONS}. */
    @Transactional(readOnly = true)
    public GitHubTokenScope forRun(UUID runId) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElseThrow(() -> notFound(runId));
        List<String> ownerRepos = graphSnapshotBuilder.reposForRun(run).stream()
                .map(GitRepo::getUrl)
                .map(RepoNameUtil::githubOwnerRepo)
                .flatMap(Optional::stream)
                .toList();
        return GitHubTokenScope.of(ownerRepos);
    }

    /** A single repository's scope — for a caller with no run in hand. Empty when it is not GitHub-hosted. */
    @Transactional(readOnly = true)
    public GitHubTokenScope forRepo(UUID gitRepoId) {
        GitRepo repo = gitRepoRepository.findById(gitRepoId).orElseThrow(() -> notFoundRepo(gitRepoId));
        Optional<String> ownerRepo = RepoNameUtil.githubOwnerRepo(repo.getUrl());
        return GitHubTokenScope.of(ownerRepo.map(List::of).orElse(List.of()));
    }

    private static NotFoundException notFound(UUID runId) {
        return new NotFoundException("Workflow run not found: " + runId);
    }

    private static NotFoundException notFoundRepo(UUID gitRepoId) {
        return new NotFoundException("Git repo not found: " + gitRepoId);
    }
}
