package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubMergeRefusedException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.GitHubTokenMintException;
import com.choruskube.core.exception.PullRequestMergeException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.PullRequestState;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private GitHubCredentialResolver credentialResolver;

    @Mock
    private GitHubAppService gitHubAppService;

    @Mock
    private AuditSink auditSink;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
    private final UUID runId = UUID.randomUUID();
    private final UUID gitRepoId = UUID.randomUUID();

    private PullRequestMergeService service() {
        return new PullRequestMergeService(
                prRepo, gitRepoRepo, credentialResolver, gitHubAppService, auditSink, objectMapper, clock);
    }

    @Test
    void noRows_returnsEmptyOutcomeAndNeverCallsCredential() {
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of());

        var outcome = service().mergeAll(runId, PullRequestMergeMethod.squash);

        assertThat(outcome.isEmpty()).isTrue();
        verifyNoInteractions(credentialResolver, gitHubAppService);
    }

    @Test
    void everyRowAlreadyMergedInDb_skipsWithNoCredentialOrGitHubCall() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        pr.setMergedAt(Instant.parse("2026-01-02T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();

        var outcome = service().mergeAll(runId, PullRequestMergeMethod.squash);

        assertThat(outcome.results()).hasSize(1);
        assertThat(outcome.results().get(0).kind()).isEqualTo(PullRequestMergeService.Kind.ALREADY_MERGED);
        verifyNoInteractions(credentialResolver, gitHubAppService);
    }

    @Test
    void credentialFailure_throwsConflictNamingTheOperatorFacingDetail() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId))
                .thenThrow(new IllegalStateException("No GitHub credential configured"));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .isInstanceOf(PullRequestMergeException.class)
                .hasMessageContaining("No GitHub credential configured")
                .hasMessageContaining("The gate is still open");
        verifyNoInteractions(auditSink);
    }

    @Test
    void credentialRateLimited_usesRetryWordingInsteadOfNoCredential() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        GitHubTokenMintException rateLimited =
                new GitHubTokenMintException(403, "12345", new GitHubRateLimitHints(30, null, null));
        when(credentialResolver.getTokenForRun(runId))
                .thenThrow(new IllegalStateException("Failed to mint installation token", rateLimited));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .isInstanceOf(PullRequestMergeException.class)
                .hasMessageContaining("retry after 30s")
                .hasMessageContaining("The gate is still open");
    }

    @Test
    void alreadyMergedOnGitHub_skipsWithNoMergeCall() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("closed", true, false, null, "sha1", "main"));

        var outcome = service().mergeAll(runId, PullRequestMergeMethod.squash);

        assertThat(outcome.results().get(0).kind()).isEqualTo(PullRequestMergeService.Kind.ALREADY_MERGED);
        verify(gitHubAppService, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
    }

    @Test
    void closedUnmerged_isSkippedWithNoMergeCall() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("closed", false, false, null, "sha1", "main"));

        var outcome = service().mergeAll(runId, PullRequestMergeMethod.squash);

        assertThat(outcome.results().get(0).kind()).isEqualTo(PullRequestMergeService.Kind.SKIPPED_CLOSED);
        verify(gitHubAppService, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
    }

    @Test
    void draftBlocksBeforeAnyMerge_evenWhenAnotherPrIsOpen_andRecordsNoAudit() {
        RunPullRequest draft = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        RunPullRequest open = pr(2, Instant.parse("2026-01-02T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(draft, open));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("open", false, true, null, "sha1", "main"));
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 2))
                .thenReturn(detail("open", false, false, true, "sha2", "main"));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .isInstanceOf(PullRequestMergeException.class)
                .hasMessageContaining("is a draft");
        verify(gitHubAppService, never()).mergePullRequest(any(), any(), anyInt(), any(), any());
        verifyNoInteractions(auditSink);
    }

    @Test
    void conflicts_blockBeforeAnyMerge() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("open", false, false, false, "sha1", "main"));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .isInstanceOf(PullRequestMergeException.class)
                .hasMessageContaining("has merge conflicts with main");
    }

    @Test
    void mergesInRegistrationOrder_stopsAtFirstRefusal_andAuditsOnlyTheMergedOne() {
        RunPullRequest first = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        RunPullRequest second = pr(2, Instant.parse("2026-01-02T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(first, second));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("open", false, false, true, "sha1", "main"));
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 2))
                .thenReturn(detail("open", false, false, true, "sha2", "main"));
        when(gitHubAppService.mergePullRequest("token", "org/repo", 1, "squash", "sha1"))
                .thenReturn("mergesha1");
        GitHubMergeRefusedException refusal = new GitHubMergeRefusedException(
                405, "org/repo", 2, "At least 1 approving review is required", GitHubRateLimitHints.NONE);
        when(gitHubAppService.mergePullRequest("token", "org/repo", 2, "squash", "sha2"))
                .thenThrow(refusal);
        // Re-read after the failed merge still shows it open, so the refusal reason stands.
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 2))
                .thenReturn(detail("open", false, false, true, "sha2", "main"));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .isInstanceOf(PullRequestMergeException.class)
                .hasMessageContaining("Merged: org/repo#1")
                .hasMessageContaining("Could not merge org/repo#2")
                .hasMessageContaining("At least 1 approving review is required");

        verify(auditSink).record(eq(AuditSink.PULL_REQUESTS_MERGED), eq("workflow_run"), eq(runId), any());
    }

    @Test
    void failedMergeCallThatGitHubThenReportsMerged_countsAsMergedByThisAttempt() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("open", false, false, true, "sha1", "main"))
                .thenReturn(detail("closed", true, false, null, "sha1", "main"));
        when(gitHubAppService.mergePullRequest("token", "org/repo", 1, "squash", "sha1"))
                .thenThrow(new RuntimeException("connection reset"));

        var outcome = service().mergeAll(runId, PullRequestMergeMethod.squash);

        assertThat(outcome.results().get(0).kind()).isEqualTo(PullRequestMergeService.Kind.MERGED);
        verify(auditSink).record(eq(AuditSink.PULL_REQUESTS_MERGED), eq("workflow_run"), eq(runId), any());
    }

    @Test
    void headChanged409_producesRetryWording() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("open", false, false, true, "sha1", "main"));
        when(gitHubAppService.mergePullRequest("token", "org/repo", 1, "squash", "sha1"))
                .thenThrow(
                        new GitHubMergeRefusedException(409, "org/repo", 1, "head changed", GitHubRateLimitHints.NONE));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .hasMessageContaining("its head branch changed while merging — retry");
    }

    @Test
    void nonRateLimit403OnMerge_namesTheWritePermissionsNeeded() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("open", false, false, true, "sha1", "main"))
                .thenReturn(detail("open", false, false, true, "sha1", "main"));
        when(gitHubAppService.mergePullRequest("token", "org/repo", 1, "squash", "sha1"))
                .thenThrow(new GitHubApiException(403, "org/repo", 1, GitHubRateLimitHints.NONE));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .hasMessageContaining("needs write access to contents and pull requests");
    }

    @Test
    void prNumberIsParsedFromUrlWhenColumnIsNull() {
        RunPullRequest pr = new RunPullRequest();
        pr.setId(UUID.randomUUID());
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl("https://github.com/org/repo/pull/99");
        pr.setPrNumber(null);
        pr.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 99))
                .thenReturn(detail("closed", true, false, null, "sha1", "main"));

        var outcome = service().mergeAll(runId, PullRequestMergeMethod.squash);

        assertThat(outcome.results().get(0).kind()).isEqualTo(PullRequestMergeService.Kind.ALREADY_MERGED);
    }

    @Test
    void missingGitRepo_blocksWithAHandMergeHint() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        when(gitRepoRepo.findById(gitRepoId)).thenReturn(Optional.empty());
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .hasMessageContaining("cannot determine its repository or number");
    }

    @Test
    void readRateLimit_producesRetryWordingAsABlocker() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenThrow(new GitHubApiException(403, "org/repo", 1, new GitHubRateLimitHints(null, 0, 123L)));

        assertThatThrownBy(() -> service().mergeAll(runId, PullRequestMergeMethod.squash))
                .hasMessageContaining("GitHub rate limit reached — retry shortly");
    }

    @Test
    void nothingMerged_recordsNoAuditEvent() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        pr.setMergedAt(Instant.parse("2026-01-02T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));

        service().mergeAll(runId, PullRequestMergeMethod.squash);

        verifyNoInteractions(auditSink);
    }

    @Test
    void throwingAuditSink_neverChangesTheOutcome() {
        RunPullRequest pr = pr(1, Instant.parse("2026-01-01T00:00:00Z"));
        when(prRepo.findByWorkflowRunId(runId)).thenReturn(List.of(pr));
        stubRepo();
        when(credentialResolver.getTokenForRun(runId)).thenReturn("token");
        when(gitHubAppService.fetchPullRequestDetail("token", "org/repo", 1))
                .thenReturn(detail("open", false, false, true, "sha1", "main"));
        when(gitHubAppService.mergePullRequest("token", "org/repo", 1, "squash", "sha1"))
                .thenReturn("mergesha1");
        doThrow(new RuntimeException("db down")).when(auditSink).record(any(), any(), any(), any());

        var outcome = service().mergeAll(runId, PullRequestMergeMethod.squash);

        assertThat(outcome.results().get(0).kind()).isEqualTo(PullRequestMergeService.Kind.MERGED);
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

    // --- helpers ---

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

    private GitHubAppService.PullRequestDetail detail(
            String state, boolean merged, boolean draft, Boolean mergeable, String headSha, String baseRef) {
        return new GitHubAppService.PullRequestDetail(
                state,
                merged,
                merged ? Instant.parse("2026-01-03T00:00:00Z") : null,
                draft,
                mergeable,
                headSha,
                baseRef);
    }
}
