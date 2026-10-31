package com.choruskube.core.service;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plans merges of a run's registered pull requests for the Worker, and records what the Worker
 * reports back — idempotently: a row already recorded as merged needs no GitHub call at all, and
 * {@link #outcomeOf} drops any Worker result for a row a plan did not list. This service never
 * calls the code host itself; {@link #planMerge} tells the Worker what to inspect and merge, and
 * {@link #recordOutcome} persists its answers.
 */
@Service
public class PullRequestMergeService {

    private static final Logger log = LoggerFactory.getLogger(PullRequestMergeService.class);

    private static final Pattern PR_NUMBER_IN_URL = Pattern.compile("/pull/(\\d+)");

    private final RunPullRequestRepository prRepo;
    private final GitRepoRepository gitRepoRepo;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PullRequestMergeService(
            RunPullRequestRepository prRepo,
            GitRepoRepository gitRepoRepo,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            Clock clock) {
        this.prRepo = prRepo;
        this.gitRepoRepo = gitRepoRepo;
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

    public record MergePlan(
            PullRequestMergeMethod method, List<Result> alreadyMerged, List<PrRef> toInspect, List<String> blockers) {}

    public record WorkerPrResult(UUID rowId, Kind kind, Instant mergedAt, String mergeSha) {}

    private record ResolvedRef(PrRef ref, String blocker) {
        static ResolvedRef of(PrRef ref) {
            return new ResolvedRef(ref, null);
        }

        static ResolvedRef blocked(String blocker) {
            return new ResolvedRef(null, blocker);
        }
    }

    /** Makes no GitHub call and resolves no credential, so a row recorded as merged never costs one. */
    public MergePlan planMerge(UUID runId, PullRequestMergeMethod method) {
        List<RunPullRequest> rows = prRepo.findByWorkflowRunId(runId).stream()
                .sorted(Comparator.comparing(RunPullRequest::getCreatedAt).thenComparing(RunPullRequest::getId))
                .toList();
        List<Result> alreadyMerged = new ArrayList<>();
        List<PrRef> toInspect = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        for (RunPullRequest row : rows) {
            // A merge on GitHub cannot be undone, so a row the database already records as merged
            // — by an earlier attempt or by the reconciler — needs no GitHub call at all.
            if (row.getMergedAt() != null) {
                alreadyMerged.add(new Result(refOrFallback(row), Kind.ALREADY_MERGED, row.getMergedAt(), null));
                continue;
            }
            ResolvedRef resolved = resolveRef(row);
            if (resolved.blocker() != null) {
                blockers.add(resolved.blocker());
            } else {
                toInspect.add(resolved.ref());
            }
        }
        return new MergePlan(method, alreadyMerged, toInspect, blockers);
    }

    /**
     * The result half of {@link #planMerge}, for merges a Worker performed: joins its answers to
     * the plan's rows by id and audits the pull requests it merged. An answer for a row the plan
     * never listed is dropped, so a stray id cannot mark an unrelated row merged. A row the plan
     * already lists as {@code alreadyMerged} — recorded by an earlier write step — keeps the
     * Worker's own kind for display but is never added to the audited list, so the merge an
     * earlier step already audited is not audited a second time.
     */
    public MergeOutcome outcomeOf(UUID runId, MergePlan plan, List<WorkerPrResult> results, UUID heldApprovalId) {
        Map<UUID, PrRef> inspected =
                plan.toInspect().stream().collect(Collectors.toMap(PrRef::rowId, Function.identity(), (a, b) -> a));
        Map<UUID, Result> alreadyMerged = plan.alreadyMerged().stream()
                .collect(Collectors.toMap(r -> r.pr().rowId(), Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Result> allResults = new ArrayList<>();
        List<Result> merged = new ArrayList<>();
        for (WorkerPrResult worker : results) {
            PrRef ref = inspected.get(worker.rowId());
            if (ref != null) {
                Result result = new Result(ref, worker.kind(), worker.mergedAt(), worker.mergeSha());
                allResults.add(result);
                if (worker.kind() == Kind.MERGED) {
                    merged.add(result);
                }
                continue;
            }
            Result priorRow = alreadyMerged.remove(worker.rowId());
            if (priorRow != null) {
                allResults.add(new Result(priorRow.pr(), worker.kind(), worker.mergedAt(), worker.mergeSha()));
                continue;
            }
            log.warn(
                    "Run {}: dropping a Worker result for pull request row {}, which was not planned",
                    runId,
                    worker.rowId());
        }
        allResults.addAll(alreadyMerged.values());
        audit(runId, plan.method(), merged, null, heldApprovalId);
        return new MergeOutcome(plan.method(), allResults);
    }

    /**
     * Persists {@code outcome} onto the tracked PR rows — {@code recordOutcome} has to happen after
     * every external call the Worker made has already succeeded, so this never risks a second
     * merge attempt; it only ever writes what GitHub has already confirmed.
     */
    @Transactional
    public void recordOutcome(UUID runId, MergeOutcome outcome) {
        if (outcome.isEmpty()) {
            return;
        }
        // outcome's rows are detached reads from an earlier call; reload inside this transaction
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

    /**
     * Records one audit event naming exactly the pull requests this attempt merged — before a
     * refusal is thrown as well as on success — so a PR merged by an attempt that is then refused
     * is never left unaudited. A failure writing it is logged and never changes the merge outcome:
     * the merge it describes has already happened on GitHub.
     */
    private void audit(
            UUID runId,
            PullRequestMergeMethod method,
            List<Result> mergedThisAttempt,
            String refusedPrUrl,
            UUID heldApprovalId) {
        if (mergedThisAttempt.isEmpty()) {
            return;
        }
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("method", method.name());
            if (heldApprovalId != null) {
                // Links this merge to the gate_approval_held event whose re-check it completed.
                json.put("heldApprovalId", heldApprovalId.toString());
            }
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
