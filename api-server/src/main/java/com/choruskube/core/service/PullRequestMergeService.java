package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubMergeRefusedException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.GitHubRateLimited;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.exception.PullRequestMergeException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.PullRequestState;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges a run's registered pull requests on approval of a gate configured for it, idempotently:
 * a repeat call never merges the same pull request twice, because GitHub's own merged/closed state
 * is read before anything is attempted and a row already recorded as merged needs no GitHub call
 * at all.
 *
 * <p>Two phases. {@link #mergeAll} inspects every row not already recorded as merged, refuses the
 * whole approval before merging anything if any row is a draft, has conflicts, or cannot be read —
 * these are the failures retrying cannot help with until a human acts — then merges the rest in
 * registration order, stopping at the first refusal. {@link #recordOutcome} writes the result onto
 * the tracked rows afterward, in its own transaction, once every external call has already
 * happened — so a failure recording it can only mean a retry re-reads GitHub and converges, never
 * that a merge is attempted twice.
 */
@Service
public class PullRequestMergeService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestMergeService.class);

    /** Deep enough for any real wrapping chain, finite so a cyclic one cannot hang the request. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private static final Pattern PR_NUMBER_IN_URL = Pattern.compile("/pull/(\\d+)");

    private final RunPullRequestRepository prRepo;
    private final GitRepoRepository gitRepoRepo;
    private final GitHubCredentialResolver credentialResolver;
    private final GitHubAppService gitHubAppService;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PullRequestMergeService(
            RunPullRequestRepository prRepo,
            GitRepoRepository gitRepoRepo,
            GitHubCredentialResolver credentialResolver,
            GitHubAppService gitHubAppService,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            Clock clock) {
        this.prRepo = prRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.credentialResolver = credentialResolver;
        this.gitHubAppService = gitHubAppService;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** One pull request, identified for the merge call and for display. */
    public record PrRef(UUID rowId, String label, String prUrl, String ownerRepo, int number) {}

    public enum Kind {
        MERGED,
        ALREADY_MERGED,
        SKIPPED_CLOSED
    }

    public record Result(PrRef pr, Kind kind, Instant mergedAt, String mergeSha) {}

    public record MergeOutcome(PullRequestMergeMethod method, List<Result> results) {
        public boolean isEmpty() {
            return results.isEmpty();
        }
    }

    private record ToMerge(PrRef pr, String headSha) {}

    private record ResolvedRef(PrRef ref, String blocker) {
        static ResolvedRef of(PrRef ref) {
            return new ResolvedRef(ref, null);
        }

        static ResolvedRef blocked(String blocker) {
            return new ResolvedRef(null, blocker);
        }
    }

    /**
     * Inspects, then merges, this run's registered pull requests not already recorded as merged.
     * Returns an outcome covering every row — including rows this call never touched GitHub for —
     * for {@link #recordOutcome} to persist. Throws {@link PullRequestMergeException} (409-mapped)
     * the moment any row cannot be merged; nothing about the decision has been written by then.
     */
    public MergeOutcome mergeAll(UUID runId, PullRequestMergeMethod method) {
        List<RunPullRequest> rows = prRepo.findByWorkflowRunId(runId).stream()
                .sorted(Comparator.comparing(RunPullRequest::getCreatedAt).thenComparing(RunPullRequest::getId))
                .toList();
        if (rows.isEmpty()) {
            return new MergeOutcome(method, List.of());
        }

        List<Result> allResults = new ArrayList<>();
        List<RunPullRequest> remaining = new ArrayList<>();
        for (RunPullRequest row : rows) {
            // A merge on GitHub cannot be undone, so a row the database already records as merged
            // — by an earlier attempt or by the reconciler — needs no GitHub call at all.
            if (row.getMergedAt() != null) {
                allResults.add(new Result(refOrFallback(row), Kind.ALREADY_MERGED, row.getMergedAt(), null));
            } else {
                remaining.add(row);
            }
        }
        if (remaining.isEmpty()) {
            return new MergeOutcome(method, allResults);
        }

        String token = resolveToken(runId);

        List<String> blockers = new ArrayList<>();
        List<ToMerge> toMergeList = new ArrayList<>();
        for (RunPullRequest row : remaining) {
            ResolvedRef resolved = resolveRef(row);
            if (resolved.blocker() != null) {
                blockers.add(resolved.blocker());
                continue;
            }
            PrRef ref = resolved.ref();
            try {
                GitHubAppService.PullRequestDetail detail =
                        gitHubAppService.fetchPullRequestDetail(token, ref.ownerRepo(), ref.number());
                if (detail.merged()) {
                    Instant mergedAt = detail.mergedAt() != null ? detail.mergedAt() : clock.instant();
                    allResults.add(new Result(ref, Kind.ALREADY_MERGED, mergedAt, null));
                } else if ("closed".equals(detail.state())) {
                    allResults.add(new Result(ref, Kind.SKIPPED_CLOSED, null, null));
                } else if (detail.draft()) {
                    blockers.add(ref.label() + " is a draft — mark it ready for review or merge it by hand");
                } else if (Boolean.FALSE.equals(detail.mergeable())) {
                    blockers.add(ref.label() + " has merge conflicts with " + detail.baseRef());
                } else {
                    toMergeList.add(new ToMerge(ref, detail.headSha()));
                }
            } catch (RuntimeException e) {
                blockers.add(classifyReadFailure(e, ref.label(), ref.ownerRepo()));
            }
        }

        if (!blockers.isEmpty()) {
            // Nothing merged yet, so there is nothing to audit — every call above was a read.
            throw new PullRequestMergeException("Approval needs every pull request to be mergeable; nothing was "
                    + "merged. " + String.join("; ", blockers) + ". The gate is still open.");
        }

        List<Result> thisAttemptMerged = new ArrayList<>();
        for (ToMerge item : toMergeList) {
            try {
                String mergeSha = gitHubAppService.mergePullRequest(
                        token, item.pr().ownerRepo(), item.pr().number(), method.name(), item.headSha());
                Result merged = new Result(item.pr(), Kind.MERGED, clock.instant(), mergeSha);
                thisAttemptMerged.add(merged);
                allResults.add(merged);
            } catch (RuntimeException mergeEx) {
                Result recovered = reReadAsMerged(token, item);
                if (recovered != null) {
                    thisAttemptMerged.add(recovered);
                    allResults.add(recovered);
                    continue;
                }
                String reason = classifyMergeFailure(
                        mergeEx, item.pr().label(), item.pr().ownerRepo());
                String mergedLabels = thisAttemptMerged.isEmpty()
                        ? "none"
                        : thisAttemptMerged.stream().map(r -> r.pr().label()).collect(Collectors.joining(", "));
                audit(runId, method, thisAttemptMerged, item.pr().prUrl());
                throw new PullRequestMergeException("Merged: " + mergedLabels + ". Could not merge "
                        + item.pr().label() + ": " + reason
                        + ". The gate is still open — resolve it on GitHub (or merge it by hand) and approve again.");
            }
        }

        if (!thisAttemptMerged.isEmpty()) {
            audit(runId, method, thisAttemptMerged, null);
        }
        return new MergeOutcome(method, allResults);
    }

    /**
     * A failed merge call that GitHub then reports as merged — a race, or a lost response — still
     * counts as merged by this attempt rather than as a failure, because the call most likely
     * performed it. Returns null when the re-read itself fails or still shows it unmerged, so the
     * caller falls back to the original failure's reason.
     */
    private Result reReadAsMerged(String token, ToMerge item) {
        try {
            GitHubAppService.PullRequestDetail detail = gitHubAppService.fetchPullRequestDetail(
                    token, item.pr().ownerRepo(), item.pr().number());
            if (detail.merged()) {
                Instant mergedAt = detail.mergedAt() != null ? detail.mergedAt() : clock.instant();
                return new Result(item.pr(), Kind.MERGED, mergedAt, null);
            }
        } catch (RuntimeException ignored) {
            // The original failure's reason is what the reviewer needs; this re-read was only a
            // chance to discover the call had actually succeeded.
        }
        return null;
    }

    /**
     * Persists {@code outcome} onto the tracked PR rows — {@code recordOutcome} has to happen after
     * every external call in {@link #mergeAll} has already succeeded, so this never risks a second
     * merge attempt; it only ever writes what GitHub has already confirmed.
     */
    @Transactional
    public void recordOutcome(UUID runId, MergeOutcome outcome) {
        if (outcome.isEmpty()) {
            return;
        }
        // mergeAll's rows are detached reads from an earlier call; reload inside this transaction
        // so the write lands on managed entities.
        List<RunPullRequest> freshRows = prRepo.findByWorkflowRunId(runId);
        Map<UUID, RunPullRequest> byId = freshRows.stream().collect(Collectors.toMap(RunPullRequest::getId, r -> r));
        Instant now = clock.instant();
        for (Result result : outcome.results()) {
            RunPullRequest row = byId.get(result.pr().rowId());
            if (row == null) {
                continue;
            }
            if ((result.kind() == Kind.MERGED || result.kind() == Kind.ALREADY_MERGED) && row.getMergedAt() == null) {
                row.setMergedAt(result.mergedAt() != null ? result.mergedAt() : now);
                row.setState(PullRequestState.closed);
                prRepo.save(row);
            } else if (result.kind() == Kind.SKIPPED_CLOSED) {
                row.setState(PullRequestState.closed);
                prRepo.save(row);
            }
        }
    }

    /** The gate's result-note paragraph, or null when there was nothing to merge at all. */
    public String note(MergeOutcome outcome) {
        if (outcome.isEmpty()) {
            return null;
        }
        List<String> merged = labelsOf(outcome, Kind.MERGED);
        List<String> alreadyMerged = labelsOf(outcome, Kind.ALREADY_MERGED);
        List<String> skippedClosed = labelsOf(outcome, Kind.SKIPPED_CLOSED);

        StringBuilder sb = new StringBuilder("Pull requests merged on approval (")
                .append(outcome.method().name())
                .append("): ")
                .append(merged.isEmpty() ? "none" : String.join(", ", merged))
                .append(".");
        if (!alreadyMerged.isEmpty()) {
            sb.append(" Already merged: ")
                    .append(String.join(", ", alreadyMerged))
                    .append(".");
        }
        if (!skippedClosed.isEmpty()) {
            sb.append(" Skipped (closed without merging): ")
                    .append(String.join(", ", skippedClosed))
                    .append(".");
        }
        return sb.toString();
    }

    private static List<String> labelsOf(MergeOutcome outcome, Kind kind) {
        return outcome.results().stream()
                .filter(r -> r.kind() == kind)
                .map(r -> r.pr().label())
                .toList();
    }

    /**
     * The credential, resolved only when a row still needs a GitHub read — the caller already
     * returned before this when every row was recorded as merged. Narrows whatever the resolver
     * threw into a 409, because the raw failure can be a bare {@code IllegalStateException} that a
     * reviewer cannot act on without the operator-facing detail it already carries.
     */
    private String resolveToken(UUID runId) {
        try {
            return credentialResolver.getTokenForRun(runId);
        } catch (RuntimeException e) {
            GitHubRateLimitHints hints = findRateLimitHints(e);
            if (hints != null) {
                log.warn("GitHub rate limited while resolving a credential for run {}: {}", runId, e.getMessage());
                throw new PullRequestMergeException("GitHub rate limit reached while obtaining a credential — "
                        + retryWording(hints) + ". The gate is still open.");
            }
            String detail = (e instanceof NotFoundException || e instanceof IllegalStateException)
                    ? e.getMessage()
                    : "see server logs";
            log.warn("Could not resolve a GitHub credential for run {}: {}", runId, e.getMessage());
            throw new PullRequestMergeException("Cannot merge this run's pull requests: no usable GitHub credential — "
                    + detail + ". The gate is still open.");
        }
    }

    /**
     * Resolves a row's {@code owner/repo} and pull request number, or a human-actionable blocker
     * when either cannot be determined — a missing {@code GitRepo} row, or no number on the row and
     * none extractable from the URL.
     */
    private ResolvedRef resolveRef(RunPullRequest row) {
        GitRepo repo = gitRepoRepo.findById(row.getGitRepoId()).orElse(null);
        Integer number = row.getPrNumber();
        if (number == null && row.getPrUrl() != null) {
            Matcher m = PR_NUMBER_IN_URL.matcher(row.getPrUrl());
            if (m.find()) {
                try {
                    number = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    number = null;
                }
            }
        }
        if (repo == null || number == null) {
            return ResolvedRef.blocked(row.getPrUrl()
                    + ": cannot determine its repository or number — merge it by hand, then " + "approve again");
        }
        String ownerRepo = RepoNameUtil.deriveOwnerRepoName(repo.getUrl());
        return ResolvedRef.of(new PrRef(row.getId(), ownerRepo + "#" + number, row.getPrUrl(), ownerRepo, number));
    }

    /** For a row already recorded as merged — a display label is all it needs, never a GitHub call. */
    private PrRef refOrFallback(RunPullRequest row) {
        ResolvedRef resolved = resolveRef(row);
        if (resolved.ref() != null) {
            return resolved.ref();
        }
        return new PrRef(
                row.getId(), row.getPrUrl(), row.getPrUrl(), null, row.getPrNumber() == null ? -1 : row.getPrNumber());
    }

    private String classifyReadFailure(RuntimeException e, String label, String ownerRepo) {
        GitHubRateLimitHints hints = findRateLimitHints(e);
        if (hints != null) {
            return "GitHub rate limit reached — " + retryWording(hints);
        }
        if (e instanceof GitHubApiException api) {
            int status = api.getStatus();
            if (status == 401 || status == 403) {
                return "the GitHub credential cannot access " + ownerRepo;
            }
            if (status == 404) {
                return label + " not found (or not visible to the credential)";
            }
        }
        return "GitHub did not respond for " + label + " — retry";
    }

    private String classifyMergeFailure(RuntimeException e, String label, String ownerRepo) {
        if (e instanceof GitHubMergeRefusedException refused) {
            return refused.getStatus() == 409 ? "its head branch changed while merging — retry" : refused.getReason();
        }
        GitHubRateLimitHints hints = findRateLimitHints(e);
        if (hints != null) {
            return "GitHub rate limit reached — " + retryWording(hints);
        }
        if (e instanceof GitHubApiException api && api.getStatus() == 403) {
            return "the GitHub credential is not allowed to merge in " + ownerRepo
                    + " — it needs write access to contents and pull requests (and workflows, if the PR changes "
                    + ".github/workflows/)";
        }
        return classifyReadFailure(e, label, ownerRepo);
    }

    private static String retryWording(GitHubRateLimitHints hints) {
        return hints.retryAfterSeconds() != null ? "retry after " + hints.retryAfterSeconds() + "s" : "retry shortly";
    }

    /**
     * Whether anything in this failure's cause chain came back from GitHub saying "rate limited".
     * Bounded because a cause chain can be cyclic.
     */
    private static GitHubRateLimitHints findRateLimitHints(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof GitHubRateLimited limited
                    && limited.getRateLimitHints().indicatesRateLimit()) {
                return limited.getRateLimitHints();
            }
            Throwable next = cause.getCause();
            if (next == cause) {
                break;
            }
            cause = next;
        }
        return null;
    }

    /**
     * Records one audit event naming exactly the pull requests this attempt merged — before a
     * refusal is thrown as well as on success — so a PR merged by an attempt that is then refused
     * is never left unaudited. A failure writing it is logged and never changes the merge outcome:
     * the merge it describes has already happened on GitHub.
     */
    private void audit(UUID runId, PullRequestMergeMethod method, List<Result> mergedThisAttempt, String refusedPrUrl) {
        if (mergedThisAttempt.isEmpty()) {
            return;
        }
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("method", method.name());
            if (refusedPrUrl != null) {
                json.put("refusedPullRequest", refusedPrUrl);
            } else {
                json.putNull("refusedPullRequest");
            }
            ArrayNode prs = json.putArray("pullRequests");
            for (Result r : mergedThisAttempt) {
                ObjectNode prNode = prs.addObject();
                prNode.put("prUrl", r.pr().prUrl());
                prNode.put("ownerRepo", r.pr().ownerRepo());
                prNode.put("number", r.pr().number());
                if (r.mergeSha() != null) {
                    prNode.put("mergeSha", r.mergeSha());
                } else {
                    prNode.putNull("mergeSha");
                }
            }
            auditSink.record(
                    AuditSink.PULL_REQUESTS_MERGED, "workflow_run", runId, objectMapper.writeValueAsString(json));
        } catch (Exception e) {
            log.error("Could not record pull-requests-merged audit event for run {}: {}", runId, e.getMessage(), e);
        }
    }
}
