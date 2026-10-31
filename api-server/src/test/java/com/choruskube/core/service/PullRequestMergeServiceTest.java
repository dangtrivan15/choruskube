package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.PullRequestState;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PullRequestMergeServiceTest {

    @Mock
    private RunPullRequestRepository prRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private AuditSink auditSink;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
    private final UUID runId = UUID.randomUUID();
    private final UUID gitRepoId = UUID.randomUUID();

    private PullRequestMergeService service() {
        return new PullRequestMergeService(prRepo, gitRepoRepo, auditSink, objectMapper, clock);
    }

    @Test
    void recordOutcome_setsFieldsAndNeverOverwritesExistingMergedAt() {
        RunPullRequest merged = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        RunPullRequest alreadyMerged = pr(2, Instant.parse("2026-01-01T00:00:00Z"));
        Instant priorMergedAt = Instant.parse("2025-01-01T00:00:00Z");
        alreadyMerged.setMergedAt(priorMergedAt);
        RunPullRequest closed = pr(3, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(merged, alreadyMerged, closed));

        var mergedRef =
                new PullRequestMergeService.PrRef(merged.getId(), "org/repo#1", merged.getPrUrl(), "org/repo", 1);
        var alreadyRef = new PullRequestMergeService.PrRef(
                alreadyMerged.getId(), "org/repo#2", alreadyMerged.getPrUrl(), "org/repo", 2);
        var closedRef =
                new PullRequestMergeService.PrRef(closed.getId(), "org/repo#3", closed.getPrUrl(), "org/repo", 3);
        var outcome = new PullRequestMergeService.MergeOutcome(
                PullRequestMergeMethod.squash,
                List.of(
                        new PullRequestMergeService.Result(
                                mergedRef,
                                PullRequestMergeService.Kind.MERGED,
                                Instant.parse("2026-10-06T12:00:00Z"),
                                "sha"),
                        new PullRequestMergeService.Result(
                                alreadyRef, PullRequestMergeService.Kind.ALREADY_MERGED, priorMergedAt, null),
                        new PullRequestMergeService.Result(
                                closedRef, PullRequestMergeService.Kind.SKIPPED_CLOSED, null, null)));

        service().recordOutcome(runId, outcome);

        assertThat(merged.getMergedAt()).isEqualTo(Instant.parse("2026-10-06T12:00:00Z"));
        assertThat(merged.getState()).isEqualTo(PullRequestState.closed);
        assertThat(alreadyMerged.getMergedAt()).isEqualTo(priorMergedAt);
        assertThat(closed.getState()).isEqualTo(PullRequestState.closed);
        assertThat(closed.getMergedAt()).isNull();
        verifyNoInteractions(auditSink);
    }

    @Test
    void recordOutcome_emptyOutcome_isANoOp() {
        service()
                .recordOutcome(
                        runId, new PullRequestMergeService.MergeOutcome(PullRequestMergeMethod.squash, List.of()));

        verifyNoInteractions(prRepo);
    }

    @Test
    void note_combinesMergedAlreadyMergedAndSkippedClosed() {
        var mergedRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#1", "u1", "org/repo", 1);
        var alreadyRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#2", "u2", "org/repo", 2);
        var closedRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#3", "u3", "org/repo", 3);
        var outcome = new PullRequestMergeService.MergeOutcome(
                PullRequestMergeMethod.squash,
                List.of(
                        new PullRequestMergeService.Result(
                                mergedRef, PullRequestMergeService.Kind.MERGED, clock.instant(), "sha"),
                        new PullRequestMergeService.Result(
                                alreadyRef, PullRequestMergeService.Kind.ALREADY_MERGED, clock.instant(), null),
                        new PullRequestMergeService.Result(
                                closedRef, PullRequestMergeService.Kind.SKIPPED_CLOSED, null, null)));

        String note = service().note(outcome);

        assertThat(note)
                .startsWith("Pull requests merged on approval (squash): org/repo#1.")
                .contains("Already merged: org/repo#2.")
                .contains("Skipped (closed without merging): org/repo#3.");
    }

    @Test
    void note_emptyOutcome_isNull() {
        assertThat(service().note(new PullRequestMergeService.MergeOutcome(PullRequestMergeMethod.squash, List.of())))
                .isNull();
    }

    @Test
    void note_nothingMergedThisTime_saysNone() {
        var alreadyRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#2", "u2", "org/repo", 2);
        var outcome = new PullRequestMergeService.MergeOutcome(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(
                        alreadyRef, PullRequestMergeService.Kind.ALREADY_MERGED, clock.instant(), null)));

        assertThat(service().note(outcome)).startsWith("Pull requests merged on approval (squash): none.");
    }

    @Test
    void planMerge_readsNoGitHub_andListsBlockers() {
        RunPullRequest merged = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        merged.setMergedAt(Instant.parse("2026-01-02T00:00:00Z"));
        RunPullRequest unresolvable = pr(2, Instant.parse("2026-01-02T00:00:00Z"));
        unresolvable.setPrNumber(null);
        unresolvable.setPrUrl("https://example.com/not-a-pull-request");
        RunPullRequest open = pr(3, Instant.parse("2026-01-03T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(open, unresolvable, merged));
        stubRepo();

        var plan = service().planMerge(runId, PullRequestMergeMethod.squash);

        assertThat(plan.method()).isEqualTo(PullRequestMergeMethod.squash);
        assertThat(plan.alreadyMerged()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(PullRequestMergeService.Kind.ALREADY_MERGED);
            assertThat(r.pr().rowId()).isEqualTo(merged.getId());
            assertThat(r.mergedAt()).isEqualTo(merged.getMergedAt());
        });
        assertThat(plan.blockers())
                .singleElement()
                .asString()
                .contains("https://example.com/not-a-pull-request")
                .contains("cannot determine its repository or number");
        assertThat(plan.toInspect()).singleElement().satisfies(ref -> {
            assertThat(ref.rowId()).isEqualTo(open.getId());
            assertThat(ref.ownerRepo()).isEqualTo("org/repo");
            assertThat(ref.number()).isEqualTo(3);
        });
        verifyNoInteractions(auditSink);
    }

    @Test
    void planMerge_prNumberIsParsedFromUrlWhenColumnIsNull() {
        RunPullRequest pr = new RunPullRequest();
        pr.setId(UUID.randomUUID());
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl("https://github.com/org/repo/pull/99");
        pr.setPrNumber(null);
        pr.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();

        var plan = service().planMerge(runId, PullRequestMergeMethod.squash);

        assertThat(plan.blockers()).isEmpty();
        assertThat(plan.toInspect()).singleElement().satisfies(ref -> {
            assertThat(ref.rowId()).isEqualTo(pr.getId());
            assertThat(ref.ownerRepo()).isEqualTo("org/repo");
            assertThat(ref.number()).isEqualTo(99);
        });
    }

    @Test
    void planMerge_missingGitRepo_blocksWithAHandMergeHint() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        when(gitRepoRepo.findById(gitRepoId)).thenReturn(Optional.empty());

        var plan = service().planMerge(runId, PullRequestMergeMethod.squash);

        assertThat(plan.blockers()).singleElement().asString().contains("cannot determine its repository or number");
        assertThat(plan.toInspect()).isEmpty();
    }

    @Test
    void outcomeOf_auditsOnlyWhatTheWorkerMerged() throws Exception {
        var alreadyRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#1", "u1", "org/repo", 1);
        var mergedRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#2", "u2", "org/repo", 2);
        var raceRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#3", "u3", "org/repo", 3);
        var plan = new PullRequestMergeService.MergePlan(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(
                        alreadyRef, PullRequestMergeService.Kind.ALREADY_MERGED, clock.instant(), null)),
                List.of(mergedRef, raceRef),
                List.of());
        Instant mergedAt = Instant.parse("2026-10-06T11:00:00Z");
        UUID heldApprovalId = UUID.randomUUID();

        var outcome = service()
                .outcomeOf(
                        runId,
                        plan,
                        List.of(
                                new PullRequestMergeService.WorkerPrResult(
                                        mergedRef.rowId(), PullRequestMergeService.Kind.MERGED, mergedAt, "mergesha2"),
                                new PullRequestMergeService.WorkerPrResult(
                                        raceRef.rowId(), PullRequestMergeService.Kind.ALREADY_MERGED, mergedAt, null)),
                        heldApprovalId);

        assertThat(outcome.method()).isEqualTo(PullRequestMergeMethod.squash);
        assertThat(outcome.results())
                .extracting(r -> r.pr().rowId(), PullRequestMergeService.Result::kind)
                .containsExactlyInAnyOrder(
                        tuple(alreadyRef.rowId(), PullRequestMergeService.Kind.ALREADY_MERGED),
                        tuple(mergedRef.rowId(), PullRequestMergeService.Kind.MERGED),
                        tuple(raceRef.rowId(), PullRequestMergeService.Kind.ALREADY_MERGED));
        JsonNode audit = objectMapper.readTree(capturedAuditDetail());
        assertThat(audit.get("method").asText()).isEqualTo("squash");
        assertThat(audit.get("heldApprovalId").asText()).isEqualTo(heldApprovalId.toString());
        assertThat(audit.get("refusedPullRequest").isNull()).isTrue();
        assertThat(audit.get("pullRequests")).hasSize(1);
        assertThat(audit.get("pullRequests").get(0).get("prUrl").asText()).isEqualTo("u2");
        assertThat(audit.get("pullRequests").get(0).get("mergeSha").asText()).isEqualTo("mergesha2");
    }

    @Test
    void outcomeOf_recordsNoAuditWhenTheWorkerMergedNothing() {
        var ref = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#1", "u1", "org/repo", 1);
        var plan = new PullRequestMergeService.MergePlan(
                PullRequestMergeMethod.squash, List.of(), List.of(ref), List.of());

        var outcome = service()
                .outcomeOf(
                        runId,
                        plan,
                        List.of(new PullRequestMergeService.WorkerPrResult(
                                ref.rowId(), PullRequestMergeService.Kind.SKIPPED_CLOSED, null, null)),
                        null);

        assertThat(outcome.results()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(PullRequestMergeService.Kind.SKIPPED_CLOSED);
            assertThat(r.pr()).isEqualTo(ref);
        });
        verifyNoInteractions(auditSink);
    }

    @Test
    void outcomeOf_ignoresAResultForARowThePlanNeverListed() {
        var ref = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#1", "u1", "org/repo", 1);
        var plan = new PullRequestMergeService.MergePlan(
                PullRequestMergeMethod.squash, List.of(), List.of(ref), List.of());

        var outcome = service()
                .outcomeOf(
                        runId,
                        plan,
                        List.of(new PullRequestMergeService.WorkerPrResult(
                                UUID.randomUUID(), PullRequestMergeService.Kind.MERGED, clock.instant(), "sha")),
                        null);

        assertThat(outcome.isEmpty()).isTrue();
        verifyNoInteractions(auditSink);
    }

    @Test
    void outcomeOf_rowRecordedByAnEarlierStep_keepsWorkersKind_auditsOnce() {
        var alreadyRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#1", "u1", "org/repo", 1);
        var plan = new PullRequestMergeService.MergePlan(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(
                        alreadyRef, PullRequestMergeService.Kind.ALREADY_MERGED, clock.instant(), null)),
                List.of(),
                List.of());
        Instant mergedAt = Instant.parse("2026-10-06T11:00:00Z");

        var outcome = service()
                .outcomeOf(
                        runId,
                        plan,
                        List.of(new PullRequestMergeService.WorkerPrResult(
                                alreadyRef.rowId(), PullRequestMergeService.Kind.MERGED, mergedAt, "worker-sha")),
                        null);

        // The write step already recorded this row merged, so the Worker's own report for it
        // replaces the ALREADY_MERGED placeholder, but it must never be audited a second time.
        assertThat(outcome.results()).singleElement().satisfies(r -> {
            assertThat(r.pr()).isEqualTo(alreadyRef);
            assertThat(r.kind()).isEqualTo(PullRequestMergeService.Kind.MERGED);
            assertThat(r.mergedAt()).isEqualTo(mergedAt);
            assertThat(r.mergeSha()).isEqualTo("worker-sha");
        });
        verifyNoInteractions(auditSink);
    }

    @Test
    void outcomeOf_unplannedRow_stillDropped() {
        var alreadyRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#1", "u1", "org/repo", 1);
        var inspectedRef = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#2", "u2", "org/repo", 2);
        var plan = new PullRequestMergeService.MergePlan(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(
                        alreadyRef, PullRequestMergeService.Kind.ALREADY_MERGED, clock.instant(), null)),
                List.of(inspectedRef),
                List.of());

        var outcome = service()
                .outcomeOf(
                        runId,
                        plan,
                        List.of(new PullRequestMergeService.WorkerPrResult(
                                UUID.randomUUID(), PullRequestMergeService.Kind.MERGED, clock.instant(), "sha")),
                        null);

        // The stray result names neither a toInspect row nor an alreadyMerged one, so it is
        // dropped and the alreadyMerged row still comes through untouched.
        assertThat(outcome.results()).singleElement().satisfies(r -> {
            assertThat(r.pr()).isEqualTo(alreadyRef);
            assertThat(r.kind()).isEqualTo(PullRequestMergeService.Kind.ALREADY_MERGED);
        });
        verifyNoInteractions(auditSink);
    }

    // --- helpers ---

    private String capturedAuditDetail() {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditSink).record(eq(AuditSink.PULL_REQUESTS_MERGED), eq("workflow_run"), eq(runId), detail.capture());
        return detail.getValue();
    }

    private RunPullRequest pr(int number, Instant createdAt) {
        RunPullRequest row = new RunPullRequest();
        row.setId(UUID.randomUUID());
        row.setWorkflowRunId(runId);
        row.setGitRepoId(gitRepoId);
        row.setPrUrl("https://github.com/org/repo/pull/" + number);
        row.setPrNumber(number);
        row.setCreatedAt(createdAt);
        return row;
    }

    private void stubRepo() {
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/org/repo.git");
        when(gitRepoRepo.findById(gitRepoId)).thenReturn(Optional.of(repo));
    }
}
