package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.BranchCleanupResponse;
import com.choruskube.core.dto.BranchCleanupResult;
import com.choruskube.core.dto.GraphRuntimeSnapshotResponse;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubCredentialUnavailableException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Best-effort deletion of a completed run's per-repo run branch ({@code
 * choruskube-run-{runId}}), once Final Approval lands the run in {@code completed}.
 *
 * <p>A branch is deleted only when it is not ahead of its repo's default branch — computed
 * authoritatively via a GitHub {@code compare} call at cleanup time, not from anything cached
 * locally, since the branch may have been pushed to at any point up to the moment cleanup runs.
 * Fail-safe by construction: any uncertainty about a repo (an unreadable credential, a network
 * fault, an unrecognised GitHub status) skips that repo and keeps its branch — deleting real work
 * is the one mistake this can never make. Applies per-repo independently, so one repo's fault never
 * stops another's cleanup.
 *
 * <p>Never lets a per-repo failure propagate: {@link #cleanupBranches} always records one {@link
 * BranchCleanupResult} per repo in the run and moves on to the next.
 */
@Service
public class BranchCleanupService {

    private static final Logger log = LoggerFactory.getLogger(BranchCleanupService.class);

    private static final String OUTCOME_DELETED = "DELETED";
    private static final String OUTCOME_KEPT_AHEAD = "KEPT_AHEAD";
    private static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    private static final String OUTCOME_SKIPPED_ERROR = "SKIPPED_ERROR";

    private final InternalRunService internalRunService;
    private final GitRepoRepository gitRepoRepo;
    private final GitHubCredentialResolver credentialResolver;
    private final GitHubAppService gitHubAppService;

    public BranchCleanupService(
            InternalRunService internalRunService,
            GitRepoRepository gitRepoRepo,
            GitHubCredentialResolver credentialResolver,
            GitHubAppService gitHubAppService) {
        this.internalRunService = internalRunService;
        this.gitRepoRepo = gitRepoRepo;
        this.credentialResolver = credentialResolver;
        this.gitHubAppService = gitHubAppService;
    }

    public BranchCleanupResponse cleanupBranches(UUID runId) {
        String branch = "choruskube-run-" + runId;
        GraphRuntimeSnapshotResponse snapshot = internalRunService.getGraphRuntimeSnapshot(runId);

        List<BranchCleanupResult> results = new ArrayList<>();
        for (GraphRuntimeSnapshotResponse.RuntimeRepo runtimeRepo : snapshot.repos()) {
            UUID gitRepoId;
            try {
                gitRepoId = UUID.fromString(runtimeRepo.id());
            } catch (IllegalArgumentException e) {
                log.warn(
                        "Branch cleanup for run {} skipped a repo with an unparseable id {}: {}",
                        runId,
                        runtimeRepo.id(),
                        e.getMessage());
                continue;
            }
            results.add(cleanupOne(runId, gitRepoId, branch));
        }
        return new BranchCleanupResponse(results);
    }

    private BranchCleanupResult cleanupOne(UUID runId, UUID gitRepoId, String branch) {
        String repoName = null;
        try {
            GitRepo repo = gitRepoRepo
                    .findById(gitRepoId)
                    .orElseThrow(() -> new IllegalStateException("Git repo not found: " + gitRepoId));
            repoName = repo.getName();
            String ownerRepo = RepoNameUtil.deriveOwnerRepoName(repo.getUrl());
            String token = resolveToken(runId, ownerRepo);

            int ahead = gitHubAppService.compareCommits(token, ownerRepo, repo.getDefaultBranch(), branch);
            if (ahead == 0) {
                gitHubAppService.deleteRef(token, ownerRepo, "heads/" + branch);
                return new BranchCleanupResult(gitRepoId, repoName, branch, OUTCOME_DELETED);
            }
            return new BranchCleanupResult(gitRepoId, repoName, branch, OUTCOME_KEPT_AHEAD);
        } catch (GitHubApiException e) {
            if (e.getStatus() == 404) {
                return new BranchCleanupResult(gitRepoId, repoName, branch, OUTCOME_NOT_FOUND);
            }
            log.warn(
                    "Branch cleanup for run {} repo {} failed (GitHub status {}): {}",
                    runId,
                    gitRepoId,
                    e.getStatus(),
                    e.getMessage());
            return new BranchCleanupResult(gitRepoId, repoName, branch, OUTCOME_SKIPPED_ERROR);
        } catch (Exception e) {
            // Credential resolution, network, and parse failures all land here. Never allowed to
            // propagate: an unreadable repo must not stop the rest of the run's repos from being
            // cleaned up, and it must never be mistaken for "safe to delete".
            log.warn("Branch cleanup for run {} repo {} failed: {}", runId, gitRepoId, e.getMessage());
            return new BranchCleanupResult(gitRepoId, repoName, branch, OUTCOME_SKIPPED_ERROR);
        }
    }

    /**
     * The credential, with any failure narrowed at this one call site — mirrors {@code
     * PullRequestStateService#resolveToken}, which is private and so cannot be called directly.
     * Narrowed here rather than inside {@link GitHubCredentialResolver} because that interface is
     * an OSS seam with implementations outside this repository.
     */
    private String resolveToken(UUID runId, String ownerRepo) {
        try {
            return credentialResolver.getTokenForRun(runId);
        } catch (RuntimeException e) {
            throw new GitHubCredentialUnavailableException(ownerRepo, e);
        }
    }
}
