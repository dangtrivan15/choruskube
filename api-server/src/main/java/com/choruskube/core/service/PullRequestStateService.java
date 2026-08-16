package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.PullRequestState;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Refreshes registered pull requests' merge state from GitHub, then closes any Task whose most
 * recent run's pull requests have all merged (Decisions 8 and 9). Merging the last PR is what
 * closes a Task — the agent is no longer asked to do it.
 *
 * <p>Idempotent by construction: a merged row leaves the unmerged scan permanently, and closure
 * re-validates every invariant, so a repeated tick is a no-op rather than a double write.
 */
@Service
public class PullRequestStateService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestStateService.class);

    private final RunPullRequestRepository prRepo;
    private final WorkflowRunRepository runRepo;
    private final GitRepoRepository gitRepoRepo;
    private final GitHubAppService gitHubAppService;
    private final GitHubCredentialResolver credentialResolver;
    private final TaskService taskService;

    public PullRequestStateService(
            RunPullRequestRepository prRepo,
            WorkflowRunRepository runRepo,
            GitRepoRepository gitRepoRepo,
            GitHubAppService gitHubAppService,
            GitHubCredentialResolver credentialResolver,
            TaskService taskService) {
        this.prRepo = prRepo;
        this.runRepo = runRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.gitHubAppService = gitHubAppService;
        this.credentialResolver = credentialResolver;
        this.taskService = taskService;
    }

    /**
     * One tick: refresh a batch of unmerged PRs, then try to close the Tasks behind any that just
     * merged. Per-row failures are logged and retried next tick — an unreachable GitHub, a missing
     * credential or a deleted repo delays closure, it never corrupts state.
     *
     * @return how many pull requests transitioned to merged
     */
    public int refreshBatch(int batchSize) {
        List<RunPullRequest> batch = prRepo.findUnmergedBatch(PageRequest.of(0, batchSize));
        Set<UUID> newlyMergedRunIds = new LinkedHashSet<>();
        int newlyMerged = 0;
        for (RunPullRequest pr : batch) {
            try {
                if (refreshOne(pr)) {
                    newlyMerged++;
                    newlyMergedRunIds.add(pr.getWorkflowRunId());
                }
            } catch (Exception e) {
                log.warn("PR state refresh for {} failed; will retry next tick: {}", pr.getId(), e.getMessage());
            }
        }
        for (UUID runId : newlyMergedRunIds) {
            closeTaskIfSettled(runId);
        }
        return newlyMerged;
    }

    /**
     * Refreshes one PR's state. Deliberately NOT {@code @Transactional}: it would be a
     * self-invoked call that never reaches the proxy, and none is needed — {@code prRepo.save}
     * is transactional on its own and each row is independent, with per-row failures already
     * caught by the caller.
     *
     * @return true if this PR was unmerged and is now merged
     */
    private boolean refreshOne(RunPullRequest pr) {
        if (pr.getPrNumber() == null) {
            log.debug("PR {} has no number; cannot query GitHub", pr.getId());
            return false;
        }
        GitRepo repo = gitRepoRepo.findById(pr.getGitRepoId()).orElse(null);
        if (repo == null) {
            log.debug("PR {} references a missing git repo {}", pr.getId(), pr.getGitRepoId());
            return false;
        }
        String ownerRepo = RepoNameUtil.deriveOwnerRepoName(repo.getUrl());
        String token = credentialResolver.getTokenForRun(pr.getWorkflowRunId());
        GitHubAppService.PullRequestSnapshot snapshot =
                gitHubAppService.fetchPullRequest(token, ownerRepo, pr.getPrNumber());

        pr.setState(parseState(snapshot.state()));
        pr.setMergedAt(snapshot.mergedAt());
        pr.setStateCheckedAt(Instant.now());
        prRepo.save(pr);
        return snapshot.mergedAt() != null;
    }

    /** Closes the run's Task when every PR on that run is merged. */
    private void closeTaskIfSettled(UUID runId) {
        try {
            WorkflowRun run = runRepo.findById(runId).orElse(null);
            if (run == null || run.getTaskId() == null) {
                return;
            }
            boolean allMerged = prRepo.findByWorkflowRunId(runId).stream().allMatch(pr -> pr.getMergedAt() != null);
            if (!allMerged) {
                return;
            }
            taskService.closeForMergedPullRequests(run.getTaskId());
            log.info("Closed Task {} — all pull requests on run {} are merged", run.getTaskId(), runId);
        } catch (Exception e) {
            // The Task may not be in_progress, its run may not be terminal, or it may not be the
            // Task's most recent run. All are legitimate reasons not to close; none is an error.
            log.debug("Task closure for run {} skipped: {}", runId, e.getMessage());
        }
    }

    private static PullRequestState parseState(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return PullRequestState.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.debug("Unrecognized GitHub pull request state: {}", raw);
            return null;
        }
    }
}
