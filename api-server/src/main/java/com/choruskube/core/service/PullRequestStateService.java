package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubCredentialUnavailableException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.PullRequestState;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>It is also the Autopilot's eyes. A Task is done when its pull requests are merged, and this is
 * the only thing that learns they were — so a GitHub failure nobody can fix by waiting does not
 * merely delay a closure, it freezes the dependency graph the Autopilot dispatches from while
 * leaving it looking healthy. Such a failure therefore disengages the Autopilot; see {@link
 * #persistentReason}.
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
    private final AutopilotSafetyValve autopilotSafetyValve;

    public PullRequestStateService(
            RunPullRequestRepository prRepo,
            WorkflowRunRepository runRepo,
            GitRepoRepository gitRepoRepo,
            GitHubAppService gitHubAppService,
            GitHubCredentialResolver credentialResolver,
            TaskService taskService,
            AutopilotSafetyValve autopilotSafetyValve) {
        this.prRepo = prRepo;
        this.runRepo = runRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.gitHubAppService = gitHubAppService;
        this.credentialResolver = credentialResolver;
        this.taskService = taskService;
        this.autopilotSafetyValve = autopilotSafetyValve;
    }

    /**
     * One tick: refresh a batch of unmerged PRs, then try to close the Tasks behind any that just
     * merged. A row that fails is left exactly as it was — unmerged and, since nothing writes
     * {@code stateCheckedAt} on a failure, still at the front of the least-recently-checked order
     * the next batch reads — so a transient failure costs one interval and nothing else.
     *
     * <p>A <em>persistent</em> failure additionally disengages the Autopilot — <strong>once per
     * repository</strong>, not once per batch. Once per repository, because fifty rows behind one
     * revoked credential are one fault with one remedy and the first reason is the one a human
     * needs. Not once per batch, because a batch is only single-scoped here: the unmerged scan is
     * installation-wide, so downstream one batch spans organisations and a single stop would land on
     * whichever repository failed first — silencing one organisation's Autopilot over another's
     * broken credential. The repository is what carries the scope ({@link
     * AutopilotSafetyValve#disengageForExternalFailure}), so it is what the reasons are keyed on.
     * Two repositories that share a scope simply resolve to the same Autopilot, and the statement's
     * {@code engaged} guard makes the second call a no-op.
     *
     * <p>The remaining rows are still attempted: a 404 on one repository says nothing about another,
     * and giving up on the batch would stop Tasks closing for work that is genuinely finished.
     *
     * <p><strong>Closures run before the stops, and each stop is contained.</strong> Disengaging is
     * the reaction to work that could not be observed; closing is the record of work that was. A
     * valve call that throws — a scope whose ownership cannot be resolved, a database blip — must
     * not strand every pull request in the batch that genuinely merged, and it must not cost the
     * next scope its stop either.
     *
     * @return how many pull requests transitioned to merged
     */
    public int refreshBatch(int batchSize) {
        List<RunPullRequest> batch = prRepo.findUnmergedBatch(PageRequest.of(0, batchSize));
        Set<UUID> newlyMergedRunIds = new LinkedHashSet<>();
        // Insertion-ordered and first-wins per repository: the reason a human acts on is the one
        // from the first row that failed, exactly as it was when this was a single reason.
        Map<UUID, String> reasonByGitRepoId = new LinkedHashMap<>();
        int newlyMerged = 0;
        for (RunPullRequest pr : batch) {
            try {
                if (refreshOne(pr)) {
                    newlyMerged++;
                    newlyMergedRunIds.add(pr.getWorkflowRunId());
                }
            } catch (Exception e) {
                String reason = persistentReason(e);
                if (reason == null) {
                    log.warn("PR state refresh for {} failed; will retry next tick: {}", pr.getId(), e.getMessage());
                } else if (reasonByGitRepoId.putIfAbsent(pr.getGitRepoId(), reason) == null) {
                    // The cause is chained rather than flattened into the message: neither exception
                    // interpolates a response body into its own text, and this is the one place with
                    // enough context to be worth a stack trace.
                    log.error("PR state refresh for {} failed in a way waiting cannot fix: {}", pr.getId(), reason, e);
                } else {
                    log.warn("PR state refresh for {} also failed persistently: {}", pr.getId(), reason);
                }
            }
        }
        for (UUID runId : newlyMergedRunIds) {
            closeTaskIfSettled(runId);
        }
        reasonByGitRepoId.forEach(this::disengageOwnerOf);
        return newlyMerged;
    }

    /**
     * Stops the Autopilot that owns one repository, and never more than that.
     *
     * <p>Contained rather than allowed to propagate: the scopes in a batch are independent, so a
     * resolution that fails for one must not take the stop away from the next. The reconciler's own
     * catch is a batch-wide boundary and would.
     */
    private void disengageOwnerOf(UUID gitRepoId, String reason) {
        try {
            autopilotSafetyValve.disengageForExternalFailure("git_repo", gitRepoId, reason);
        } catch (Exception e) {
            log.error("Could not disengage the Autopilot owning git repo {}: {}", gitRepoId, e.getMessage(), e);
        }
    }

    /**
     * Whether a failure means "a human has to do something", and if so what to tell them.
     *
     * <p>The strictness rule the Autopilot rests on: if we can no longer tell what is merged, stop
     * automating. Everything that can come right on its own — 429, 5xx, a timeout, a reset
     * connection — is left to the next tick and never disengages, because a two-minute retry loop
     * recovers from all of them without a human and disengaging on one would make the Autopilot
     * useless. Everything that cannot — a credential that is refused or absent, a repository that is
     * gone — disengages on the first occurrence rather than the third.
     *
     * <p><strong>Transient is the closed list, and persistent is the default.</strong> That
     * direction is the rule itself: a false stop costs an operator one click, a false continue costs
     * an Autopilot dispatching against a graph it can no longer read. Enumerating the persistent
     * statuses instead put every status nobody had thought of on the unsafe side — 410 Gone and 451
     * are as permanent as 404 and used to retry every two minutes forever, and a 3xx counted as
     * transient too, since this client does not follow redirects and would never have resolved one.
     *
     * <p>Statuses are therefore classified by what can come right on its own: 429 and 5xx, which a
     * two-minute retry loop recovers from without a human, and which would make the Autopilot
     * useless if they stopped it. Everything else needs somebody. Exceptions carrying no status at
     * all — a timeout, a reset connection — stay transient by nature: there was no response to
     * classify, and the next tick is the right answer.
     *
     * <p>Two judgement calls worth stating. A 403 is persistent although GitHub also uses it for
     * secondary rate limits, so a heavily rate-limited installation can be stopped by something that
     * would have cleared itself; the trade is deliberate, and it is the same trade as the default
     * above. And a credential that cannot be resolved is persistent whatever went wrong underneath,
     * including a transient failure while minting an installation token: not having a credential at
     * all is not a state to keep automating through.
     *
     * @return the reason to record on the Autopilot, or null when the failure is transient
     */
    private static String persistentReason(Exception e) {
        if (e instanceof GitHubCredentialUnavailableException) {
            return e.getMessage() + " — check the GitHub credential configuration";
        }
        if (e instanceof GitHubApiException api) {
            int status = api.getStatus();
            if (status == 429 || status >= 500) {
                return null;
            }
            return switch (status) {
                case 401 -> api.getMessage() + " — check the GitHub credential";
                case 403 -> api.getMessage() + " — the GitHub credential is not allowed to read that repository";
                case 404 ->
                    api.getMessage() + " — the repository or pull request is gone, or not visible to the "
                            + "GitHub credential";
                default -> api.getMessage() + " — GitHub will keep answering that until somebody changes something";
            };
        }
        return null;
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
        String token = resolveToken(pr.getWorkflowRunId(), ownerRepo);
        GitHubAppService.PullRequestSnapshot snapshot =
                gitHubAppService.fetchPullRequest(token, ownerRepo, pr.getPrNumber());

        pr.setState(parseState(snapshot.state()));
        pr.setMergedAt(snapshot.mergedAt());
        pr.setStateCheckedAt(Instant.now());
        prRepo.save(pr);
        return snapshot.mergedAt() != null;
    }

    /**
     * The credential, with any failure narrowed at this one call site.
     *
     * <p>Narrowed here rather than inside {@link GitHubCredentialResolver} because that interface is
     * an OSS seam with implementations outside this repository: classifying at the point of use
     * covers all of them without asking any of them to change. Narrowed at all because {@code
     * refreshOne} also saves a row and parses a URL, and the resolver's own {@link
     * IllegalStateException} would otherwise be indistinguishable from theirs one catch block later.
     */
    private String resolveToken(UUID runId, String ownerRepo) {
        try {
            return credentialResolver.getTokenForRun(runId);
        } catch (RuntimeException e) {
            throw new GitHubCredentialUnavailableException(ownerRepo, e);
        }
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
