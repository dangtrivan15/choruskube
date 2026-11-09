package com.choruskube.core.service;

import com.choruskube.core.dto.BranchCleanupPlanResponse;
import com.choruskube.core.dto.GraphRuntimeSnapshotResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Plans the run-end branch cleanup: a read-only list of a completed run's repos for the
 * orchestrator to carry to the run's Worker, which holds the credential, compares each run branch
 * with its default branch and deletes it only when it is not ahead. Nothing here calls GitHub.
 */
@Service
public class BranchCleanupService {

    private static final Logger log = LoggerFactory.getLogger(BranchCleanupService.class);

    private final InternalRunService internalRunService;
    private final GitRepoRepository gitRepoRepo;

    public BranchCleanupService(InternalRunService internalRunService, GitRepoRepository gitRepoRepo) {
        this.internalRunService = internalRunService;
        this.gitRepoRepo = gitRepoRepo;
    }

    /**
     * Each of the run's repos' id, {@code owner/repo} slug and default branch, in snapshot order.
     * Resolves no credential and calls no GitHub API — the Worker does both itself, with the repos
     * this returns.
     *
     * @throws ConflictException if the run has not reached {@code completed}
     */
    public BranchCleanupPlanResponse plan(UUID runId) {
        String status = internalRunService.getRunStatus(runId).status();
        if (!"completed".equals(status)) {
            throw new ConflictException("Branch cleanup is planned only for a completed run");
        }

        GraphRuntimeSnapshotResponse snapshot = internalRunService.getGraphRuntimeSnapshot(runId);
        List<BranchCleanupPlanResponse.Repo> repos = new ArrayList<>();
        for (GraphRuntimeSnapshotResponse.RuntimeRepo runtimeRepo : snapshot.repos()) {
            UUID gitRepoId;
            try {
                gitRepoId = UUID.fromString(runtimeRepo.id());
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Branch cleanup plan for run {} skipped a repo with an unparseable id {}: {}",
                        runId,
                        runtimeRepo.id(),
                        e.getMessage());
                continue;
            }
            GitRepo repo = gitRepoRepo.findById(gitRepoId).orElse(null);
            if (repo == null) {
                log.warn("Branch cleanup plan for run {} skipped missing repo {}", runId, gitRepoId);
                continue;
            }
            Optional<String> ownerRepo = RepoNameUtil.githubOwnerRepo(repo.getUrl());
            if (ownerRepo.isEmpty()) {
                log.warn("Branch cleanup plan for run {} skipped repo {} not hosted on GitHub", runId, gitRepoId);
                continue;
            }
            repos.add(new BranchCleanupPlanResponse.Repo(gitRepoId, ownerRepo.get(), repo.getDefaultBranch()));
        }
        return new BranchCleanupPlanResponse(repos);
    }
}
