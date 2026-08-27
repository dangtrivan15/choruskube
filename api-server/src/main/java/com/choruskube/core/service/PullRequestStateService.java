package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubCredentialUnavailableException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.GitHubRateLimited;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.PullRequestState;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Refreshes registered pull requests' merge state from GitHub, then closes any Task whose most
 * recent run's pull requests have all merged. Merging the last PR is what
 * closes a Task — the agent is no longer asked to do it.
 *
 * <p>Idempotent by construction: a merged row leaves the unmerged scan permanently, and closure
 * re-validates every invariant, so a repeated tick is a no-op rather than a double write.
 *
 * <p>It is also the Autopilot's eyes. A Task is done when its pull requests are merged, and this is
 * the only thing that learns they were — so a GitHub failure nobody can fix by waiting does not
 * merely delay a closure, it leaves a Task open that the Autopilot's dependents are waiting on.
 *
 * <p><strong>How wide the reaction is depends on how wide the fault is</strong> — see {@link
 * FaultResponse}. A fault confined to one pull request quarantines that row and nothing else; the
 * Autopilot keeps dispatching every Task the fault does not touch, and the stuck ones are reported
 * rather than hidden. Only a fault that makes the whole scope unreadable — a credential that is
 * refused or absent — stops the Autopilot outright.
 *
 * <p>That asymmetry is safe because a failed read is fail-closed: {@link #refreshOne} throws before
 * it touches the entity, so an unread pull request keeps its state and its Task stays open. The
 * Autopilot can therefore be made to start <em>less</em> than it might have, never something it
 * should not have.
 */
@Service
public class PullRequestStateService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestStateService.class);

    /** Deep enough for any real wrapping chain, finite so a cyclic one cannot hang the scheduler. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private final RunPullRequestRepository prRepo;
    private final WorkflowRunRepository runRepo;
    private final GitRepoRepository gitRepoRepo;
    private final GitHubAppService gitHubAppService;
    private final GitHubCredentialResolver credentialResolver;
    private final TaskService taskService;
    private final AutopilotSafetyValve autopilotSafetyValve;
    private final Duration backoffBase;
    private final Duration backoffCap;

    public PullRequestStateService(
            RunPullRequestRepository prRepo,
            WorkflowRunRepository runRepo,
            GitRepoRepository gitRepoRepo,
            GitHubAppService gitHubAppService,
            GitHubCredentialResolver credentialResolver,
            TaskService taskService,
            AutopilotSafetyValve autopilotSafetyValve,
            @Value("${choruskube.reconciler.pull-request-state.backoff-base:PT2M}") Duration backoffBase,
            @Value("${choruskube.reconciler.pull-request-state.backoff-cap:PT30M}") Duration backoffCap) {
        this.prRepo = prRepo;
        this.runRepo = runRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.gitHubAppService = gitHubAppService;
        this.credentialResolver = credentialResolver;
        this.taskService = taskService;
        this.autopilotSafetyValve = autopilotSafetyValve;
        // A base below the tick interval buys nothing — the row simply becomes due again before
        // anything looks at it — but a base of zero is a defect, not a tuning choice: it is the
        // pre-V17 behaviour, and it reintroduces the starvation this exists to remove.
        if (backoffBase.isZero() || backoffBase.isNegative()) {
            throw new IllegalArgumentException(
                    "pull-request-state.backoff-base must be positive; a zero delay is what starved the scan");
        }
        if (backoffCap.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("pull-request-state.backoff-cap must be at least the base");
        }
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
    }

    /**
     * One tick: refresh a batch of unmerged PRs, then try to close the Tasks behind any that just
     * merged. A row that fails is deferred by {@link #backOff} rather than left where it was: it
     * keeps its state, but yields its place in the scan for a delay that doubles with each
     * consecutive failure. A single transient failure therefore still costs about one interval,
     * while a row nothing can fix stops crowding healthy rows out of the batch.
     *
     * <p>A failure classified {@link FaultResponse#QUARANTINE} is flagged on its own row and goes no
     * further; the Autopilot is told about it through the status panel, not stopped by it.
     *
     * <p>A {@link FaultResponse#DISENGAGE} failure additionally disengages the Autopilot — <strong>once per
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
        Instant now = Instant.now();
        List<RunPullRequest> batch = prRepo.findUnmergedBatch(now, PageRequest.of(0, batchSize));
        Set<UUID> newlyMergedRunIds = new LinkedHashSet<>();
        // Insertion-ordered and first-wins per repository: the reason a human acts on is the one
        // from the first row that failed, exactly as it was when this was a single reason.
        Map<UUID, String> reasonByGitRepoId = new LinkedHashMap<>();
        int newlyMerged = 0;
        for (RunPullRequest pr : batch) {
            try {
                if (refreshOne(pr, now)) {
                    newlyMerged++;
                    newlyMergedRunIds.add(pr.getWorkflowRunId());
                }
            } catch (Exception e) {
                // Before anything is decided about the Autopilot: this row yields its place.
                // Independent of the classification below, because the two answer different
                // questions — whether a human must act, and when this row may be tried again.
                backOff(pr, now);
                Fault fault = classifyFault(e);
                String reason = fault.response() == FaultResponse.DISENGAGE ? fault.reason() : null;
                if (fault.response() == FaultResponse.QUARANTINE) {
                    // Confined to this row: the Task behind it cannot close, and nothing else is
                    // affected. Recorded on the row rather than on the Autopilot, so the panel can
                    // name the Task without the reconciler needing to know which Autopilot owns it.
                    quarantine(pr, fault.reason(), e);
                } else if (reason == null) {
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
     * What a failure to read one pull request means for the Autopilot.
     *
     * <p>The three answers differ in <em>blast radius</em>, which is the only axis that matters
     * here. A read failure is fail-closed by construction — {@link #refreshOne} throws before it
     * touches the entity, so an unread pull request keeps whatever state it had and its Task stays
     * open. The Autopilot therefore cannot be misled into starting work it should not; the worst it
     * can do is start <em>less</em> than it might have. That asymmetry is what makes anything
     * short of a full stop safe.
     */
    enum FaultResponse {
        /** Waiting fixes it. Back off and say nothing — the next tick is the whole remedy. */
        RETRY,

        /**
         * Permanent, but confined to this pull request. The Task behind it can never close, so it
         * is surfaced for a human; every other Task is unaffected and dispatch continues.
         */
        QUARANTINE,

        /**
         * Permanent and wider than one pull request — a credential fault breaks every repository
         * in the scope at once. Quarantining each Task in turn would drain the roadmap one item at
         * a time instead of stopping cleanly and naming the one thing a human must fix.
         */
        DISENGAGE
    }

    /**
     * @param reason rendered verbatim in the UI — must name the resource and the fault, and must
     *     never contain a token or a response body
     */
    record Fault(FaultResponse response, String reason) {
        static final Fault RETRY = new Fault(FaultResponse.RETRY, null);

        static Fault quarantine(String reason) {
            return new Fault(FaultResponse.QUARANTINE, reason);
        }

        static Fault disengage(String reason) {
            return new Fault(FaultResponse.DISENGAGE, reason);
        }
    }

    /**
     * Stops the Autopilot that owns one repository, and never more than that.
     *
     * <p>Contained rather than allowed to propagate: the scopes in a batch are independent, so a
     * resolution that fails for one must not take the stop away from the next. The reconciler's own
     * catch is a batch-wide boundary and would.
     */
    /**
     * Flags one pull request as permanently unreadable, and stops there.
     *
     * <p>Idempotent on the timestamp: the reason is refreshed on every pass so a fault that
     * changes shape reports its current form, but {@code unreadableSince} keeps its first value so
     * the panel can say how long a Task has been stuck rather than always "just now".
     *
     * <p>Logged at WARN rather than ERROR, and only when the flag is newly set. This runs every
     * tick for as long as the condition lasts, and a row nobody intends to fix — a repository that
     * really is gone — must not reprint a stack trace forever.
     */
    private void quarantine(RunPullRequest pr, String reason, Exception e) {
        boolean firstTime = pr.getUnreadableSince() == null;
        if (firstTime) {
            pr.setUnreadableSince(Instant.now());
        }
        pr.setUnreadableReason(reason);
        prRepo.save(pr);
        if (firstTime) {
            log.warn("PR {} can no longer be read; the Task behind it cannot close: {}", pr.getId(), reason, e);
        } else {
            log.debug("PR {} still unreadable: {}", pr.getId(), reason);
        }
    }

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
     * <p><strong>A rate limit is transient whatever status carries it.</strong> GitHub answers a
     * secondary rate limit with 403 — the same status as a credential that genuinely lacks access —
     * so status alone put a heavily used installation on the persistent side and stopped it for
     * something that would have cleared in seconds. The response says which it is, but only in its
     * headers, so {@link #rateLimited} is consulted before the status is. That check reads a
     * whitelist of three parsed header values and never the body, which can echo the request's
     * {@code Authorization} header; see {@link GitHubRateLimitHints}.
     *
     * <p>The check is a <em>positive</em> signal only. Absent or malformed headers leave a 403
     * exactly as persistent as before, so the failure this can produce is missing a rate limit — the
     * problem it started from — never mistaking a revoked credential for one.
     *
     * <p>It runs over the whole cause chain rather than the exception in hand, because the second
     * way a rate limit arrives is buried: the credential resolver is a seam, so a 403 while minting
     * an installation token surfaces as a {@link GitHubCredentialUnavailableException} wrapping
     * whatever the implementation threw. Not having a credential is still persistent by default —
     * that part is unchanged — but "GitHub told us to wait" is not the same thing as "there is no
     * credential", and only the chain can tell them apart.
     *
     * @return the reason to record on the Autopilot, or null when the failure is transient
     */
    private static Fault classifyFault(Exception e) {
        // Unchanged, and deliberately ahead of everything else: a rate limit arrives as a 403 and
        // must never be read as a credential fault. See rateLimited().
        if (rateLimited(e)) {
            return Fault.RETRY;
        }

        // No credential at all is a property of the scope, not of any one repository: the next row
        // in the batch will fail identically, and so will every row after that.
        if (e instanceof GitHubCredentialUnavailableException) {
            return Fault.disengage(e.getMessage() + " — check the GitHub credential configuration");
        }
        if (e instanceof GitHubApiException api) {
            int status = api.getStatus();
            if (status == 429 || status >= 500) {
                return Fault.RETRY;
            }
            return switch (status) {
                // 401 and 403 are the credential answering for itself. One repository reporting
                // them means every repository the credential covers is about to, so stopping
                // once with the right reason beats quarantining the roadmap one Task at a time.
                case 401 -> Fault.disengage(api.getMessage() + " — check the GitHub credential");
                case 403 ->
                    Fault.disengage(
                            api.getMessage() + " — the GitHub credential is not allowed to read that repository");
                // 404 is about this pull request and says nothing about the next one: a deleted
                // PR, a deleted repository, or — the case that produced this classification — a
                // repository renamed away while another took its name.
                case 404 ->
                    Fault.quarantine(
                            api.getMessage() + " — the repository or pull request is gone, or not visible to the "
                                    + "GitHub credential");
                // Unknown, and therefore confined. 301, 410, 422 and 451 all reach here, and
                // none of them is evidence about any repository but this one. The old code
                // stopped the Autopilot for all of them because a stop was the only response it
                // had; with quarantine available, confining an unrecognised status is both the
                // conservative answer and the honest one.
                default ->
                    Fault.quarantine(
                            api.getMessage() + " — GitHub will keep answering that until somebody changes something");
            };
        }

        // Exceptions carrying no status at all — a timeout, a reset connection — stay transient by
        // nature: there was no response to classify, and the next tick is the right answer.
        return Fault.RETRY;
    }

    /**
     * Whether anything in this failure's cause chain came back from GitHub saying "rate limited".
     *
     * <p>Bounded rather than a plain {@code while (cause != null)} loop: a cause chain can be
     * cyclic — a wrapper whose cause is itself is the common accident — and this runs inside a
     * reconciler where an infinite loop is a hung scheduler thread, not a stack trace.
     */
    private static boolean rateLimited(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof GitHubRateLimited limited
                    && limited.getRateLimitHints().indicatesRateLimit()) {
                return true;
            }
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
        }
        return false;
    }

    /**
     * Refreshes one PR's state. Deliberately NOT {@code @Transactional}: it would be a
     * self-invoked call that never reaches the proxy, and none is needed — {@code prRepo.save}
     * is transactional on its own and each row is independent, with per-row failures already
     * caught by the caller.
     *
     * @return true if this PR was unmerged and is now merged
     */
    private boolean refreshOne(RunPullRequest pr, Instant now) {
        GitRepo repo = gitRepoRepo
                .findById(pr.getGitRepoId())
                // Unreachable while `run_pull_request.git_repo_id` keeps its NOT NULL foreign key
                // with no ON DELETE clause. Thrown rather than returned so that if the schema ever
                // loosens, the row takes the normal failure path and backs off, instead of
                // returning false forever and holding a batch slot on a debug log.
                .orElseThrow(() -> new IllegalStateException(
                        "PR " + pr.getId() + " references a missing git repo " + pr.getGitRepoId()));
        String ownerRepo = RepoNameUtil.deriveOwnerRepoName(repo.getUrl());
        String token = resolveToken(pr.getWorkflowRunId(), ownerRepo);
        GitHubAppService.PullRequestSnapshot snapshot =
                gitHubAppService.fetchPullRequest(token, ownerRepo, pr.getPrNumber());

        pr.setState(parseState(snapshot.state()));
        pr.setMergedAt(snapshot.mergedAt());
        pr.setStateCheckedAt(now);
        pr.setFailureCount(0);
        // GitHub answered, so whatever made this unreadable is over. Cleared here rather than only
        // on merge: a repository that was renamed back, or a credential that regained access, must
        // leave quarantine without a human touching the row.
        pr.setUnreadableSince(null);
        pr.setUnreadableReason(null);
        // Due again on the next tick. The scan takes rows whose time has passed, so "now" is the
        // way to say "no delay" without special-casing zero anywhere.
        pr.setNextCheckAt(now);
        prRepo.save(pr);
        return snapshot.mergedAt() != null;
    }

    /**
     * Defers one failed row, geometrically further each consecutive time.
     *
     * <p>The first failure delays by one base interval, so a transient blip still costs about one
     * tick — the property the old always-front behaviour was protecting. What it adds is a bound
     * for the other case: a row that cannot be fixed by waiting doubles its way to the cap and
     * stops crowding healthy rows out of the batch, instead of holding the front of the queue
     * forever.
     *
     * <p>Deliberately not the alternative one-liner of stamping {@code stateCheckedAt} on failure.
     * That also rotates the queue, but it rotates it by a full lap: with several thousand unmerged
     * rows and a batch of fifty, a row that failed once would not be retried for hours, and a pull
     * request that merged during a brief outage would keep its Task open for all of it.
     *
     * <p>Written outside any transaction of this class's own, exactly like the success path —
     * {@code prRepo.save} carries its own, each row is independent, and the caller has already
     * isolated per-row failures.
     */
    private void backOff(RunPullRequest pr, Instant now) {
        int failures = pr.getFailureCount() + 1;
        // Clamped before the shift, not after: the exponent is unbounded over time and 1L << 64 is
        // 1 in Java rather than an overflow anyone would notice.
        long multiplier = 1L << Math.min(failures - 1, 32);
        Duration delay = backoffBase.multipliedBy(multiplier);
        if (delay.compareTo(backoffCap) > 0) {
            delay = backoffCap;
        }
        pr.setFailureCount(failures);
        pr.setNextCheckAt(now.plus(delay));
        try {
            prRepo.save(pr);
        } catch (RuntimeException e) {
            // The row keeps its old due time and is retried sooner than intended — the pre-V17
            // behaviour for this one row. Never allowed to replace the original failure, which is
            // the one that says whether a human has to act.
            log.warn("Could not record the backoff for PR {}: {}", pr.getId(), e.getMessage());
        }
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
