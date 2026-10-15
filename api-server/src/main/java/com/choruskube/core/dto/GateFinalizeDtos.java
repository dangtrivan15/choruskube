package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shapes of the internal steps that finalize an approval on an asynchronous gate. The
 * orchestrator and the Worker mirror these field names as JSON tags, so a rename here silently
 * drops the field on their side.
 */
public final class GateFinalizeDtos {

    private GateFinalizeDtos() {}

    /** {@code approvalId} is null when no held approval applies to the gate. */
    public record HeldClaim(UUID approvalId) {}

    /**
     * {@code outcome}: {@code proceed}, {@code hold} or {@code refused} (with {@code reason}).
     * {@code writesRoadmap} is the orchestrator's capability signal for the split write-then-file
     * protocol; an older API omits it, which the orchestrator decodes as {@code false}.
     */
    public record PlanResponse(
            String outcome,
            String reason,
            List<CompareItem> compares,
            List<PrItem> prs,
            String mergeMethod,
            boolean writesRoadmap) {}

    public record CompareItem(UUID gitRepoId, String ownerRepo, String branch, String baseSha) {}

    public record PrItem(UUID rowId, String ownerRepo, int number, String label, String prUrl) {}

    public record HoldRequest(List<CompareResultItem> compares) {}

    public record CompareResultItem(UUID gitRepoId, int aheadBy, boolean baseGone) {}

    public record CompleteRequest(List<PrResultItem> merges, List<IssueResultItem> issues) {}

    public record MaterializeRequest(List<PrResultItem> merges) {}

    /** {@code since}: the write step's claim time, the lower bound the Worker lists issues from. */
    public record MaterializeResponse(Instant since, List<IssueRequest> issues) {}

    /** One GitHub issue the write step asks the Worker to file for a newly-created Task. */
    public record IssueRequest(UUID taskId, UUID gitRepoId, String ownerRepo, String title, String body) {}

    /** {@code failure} is set, instead of {@code number}/{@code url}, when the Worker could not file it. */
    public record IssueResultItem(UUID taskId, Integer number, String url, String failure) {}

    /** {@code kind}: {@code MERGED}, {@code ALREADY_MERGED} or {@code SKIPPED_CLOSED}; anything else is a 400. */
    public record PrResultItem(UUID rowId, String kind, Instant mergedAt, String mergeSha) {}

    /** {@code outcome}: {@code completed}, {@code held} or {@code refused} (with {@code reason}). */
    public record StepResult(String outcome, String result, String attachmentRefs, String reason) {}

    /** {@code merges}: what the Worker already merged before the refusal, recorded before the approval is voided. */
    public record RefuseRequest(String reason, List<PrResultItem> merges) {}
}
