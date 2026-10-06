package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateMilestone;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateDependencyRequest;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.DependencyCycleException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * {@link DefaultRoadmapCandidateMaterializer}: creates each candidate Milestone/Epic/Story/Task
 * through {@link InternalRunService}/{@link MilestoneService}'s existing agent-facing write paths,
 * then each candidate dependency edge through {@link InternalRunService#createDependency}, best-
 * effort per top-level candidate/edge. An {@code existingId} anchor is never created — only its
 * key (if any) is registered, and its children attach under it. A candidate item's free-text
 * {@code priority} is parsed case-insensitively onto {@link Priority} (defaulting to {@link
 * Priority#medium}); Epic {@code repos} is still dropped.
 */
class DefaultRoadmapCandidateMaterializerTest {

    private InternalRunService internalRunService;
    private MilestoneService milestoneService;
    private GitHubAppService gitHubAppService;
    private GitHubCredentialResolver gitHubCredentialResolver;
    private TaskGithubIssueRepository taskGithubIssueRepository;
    private DefaultRoadmapCandidateMaterializer materializer;
    private final UUID runId = UUID.randomUUID();
    private final UUID softwareProjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        internalRunService = Mockito.mock(InternalRunService.class);
        milestoneService = Mockito.mock(MilestoneService.class);
        gitHubAppService = Mockito.mock(GitHubAppService.class);
        gitHubCredentialResolver = Mockito.mock(GitHubCredentialResolver.class);
        taskGithubIssueRepository = Mockito.mock(TaskGithubIssueRepository.class);
        materializer = new DefaultRoadmapCandidateMaterializer(
                internalRunService,
                milestoneService,
                gitHubAppService,
                gitHubCredentialResolver,
                taskGithubIssueRepository);
    }

    private static EpicResponse epicResponse(UUID id) {
        return new EpicResponse(
                id, "t", "d", "m", "active", "medium", null, null, null, null, Instant.now(), Instant.now(), 0, null);
    }

    private static StoryResponse storyResponse(UUID id, UUID epicId) {
        return new StoryResponse(
                id, epicId, "t", "d", "backlog", "medium", null, null, null, null, Instant.now(), Instant.now());
    }

    private static TaskResponse taskResponse(UUID storyId) {
        return taskResponse(UUID.randomUUID(), storyId, "t", "d");
    }

    private static TaskResponse taskResponse(UUID id, UUID storyId, String title, String description) {
        return new TaskResponse(
                id,
                storyId,
                title,
                description,
                "open",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0L,
                Instant.now(),
                Instant.now(),
                "medium");
    }

    private static MilestoneResponse milestoneResponse(UUID id, String name) {
        return new MilestoneResponse(
                id,
                name,
                "d",
                UUID.randomUUID(),
                null,
                0,
                new MilestoneResponse.Progress(0, 0, 0, 0),
                false,
                0,
                Instant.now(),
                Instant.now());
    }

    private static RoadmapCandidatesDocument document(
            List<CandidateMilestone> milestones, List<CandidateEpicProposal> epics, List<CandidateDependency> deps) {
        return new RoadmapCandidatesDocument(milestones, epics, deps);
    }

    private static CandidateEpicProposal epic(
            String title, String priority, List<CandidateStoryProposal> stories, String key, String milestoneKey) {
        return new CandidateEpicProposal(title, "d", "m", null, priority, stories, key, milestoneKey, null);
    }

    private static CandidateEpicProposal epicAnchor(UUID existingId, List<CandidateStoryProposal> stories, String key) {
        return new CandidateEpicProposal(null, null, null, null, null, stories, key, null, existingId);
    }

    private static CandidateStoryProposal story(
            String title, List<CandidateTaskProposal> tasks, String key, String priority) {
        return new CandidateStoryProposal(title, "s-desc", tasks, key, priority, null);
    }

    private static CandidateStoryProposal storyAnchor(UUID existingId, List<CandidateTaskProposal> tasks, String key) {
        return new CandidateStoryProposal(null, null, tasks, key, null, existingId);
    }

    private static CandidateTaskProposal task(String title, String key, String priority) {
        return new CandidateTaskProposal(title, "t-desc", key, priority, null, null);
    }

    private static CandidateTaskProposal task(String title, String key, String priority, UUID repoId) {
        return new CandidateTaskProposal(title, "t-desc", key, priority, null, repoId);
    }

    private static CandidateTaskProposal taskAnchor(UUID existingId, String key) {
        return new CandidateTaskProposal(null, null, key, null, existingId, null);
    }

    private static MaterializationSummary materialize(
            DefaultRoadmapCandidateMaterializer m, UUID runId, RoadmapCandidatesDocument doc) {
        return m.materialize(runId, doc, RoadmapMaterializeMode.roadmap_candidates);
    }

    @Test
    void materializesFullEpicStoryTaskShape_inOrder() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(eq(runId), eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(internalRunService.createTask(eq(runId), eq(epicId), eq(storyId), any()))
                .thenReturn(taskResponse(storyId));

        CandidateEpicProposal candidate = epic(
                "Bulk Import",
                "High",
                List.of(story("Story 1", List.of(task("Task 1", null, null)), null, null)),
                null,
                null);

        MaterializationSummary summary = materialize(materializer, runId, document(null, List.of(candidate), null));

        assertThat(summary.createdEpicIds()).containsExactly(epicId);
        assertThat(summary.createdStoryIds()).containsExactly(storyId);
        assertThat(summary.createdTaskIds()).hasSize(1);
        assertThat(summary.errors()).isEmpty();
        assertThat(summary.createdMilestoneIds()).isEmpty();
        assertThat(summary.createdDependencyCount()).isZero();

        verify(internalRunService)
                .createEpic(eq(runId), eq(new InternalCreateEpicRequest("Bulk Import", "d", "m", Priority.high, null)));
        verify(internalRunService)
                .createStory(
                        eq(runId),
                        eq(epicId),
                        eq(new InternalCreateStoryRequest("Story 1", "s-desc", Priority.medium)));
        verify(internalRunService)
                .createTask(
                        eq(runId),
                        eq(epicId),
                        eq(storyId),
                        eq(new InternalCreateTaskRequest("Task 1", "t-desc", Priority.medium)));
        verifyNoInteractions(gitHubAppService);
    }

    @Test
    void storyAndTaskPriority_areParsedAndForwarded() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(eq(runId), eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(internalRunService.createTask(eq(runId), eq(epicId), eq(storyId), any()))
                .thenReturn(taskResponse(storyId));

        CandidateEpicProposal candidate = epic(
                "Bulk Import",
                null,
                List.of(story("Story 1", List.of(task("Task 1", null, "Low")), null, "High")),
                null,
                null);

        materialize(materializer, runId, document(null, List.of(candidate), null));

        verify(internalRunService)
                .createStory(
                        eq(runId), eq(epicId), eq(new InternalCreateStoryRequest("Story 1", "s-desc", Priority.high)));
        verify(internalRunService)
                .createTask(
                        eq(runId),
                        eq(epicId),
                        eq(storyId),
                        eq(new InternalCreateTaskRequest("Task 1", "t-desc", Priority.low)));
    }

    @Test
    void milestone_createdAndEpicMilestoneIdSet() {
        UUID epicId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(softwareProjectId);
        when(milestoneService.findOrCreate(softwareProjectId, "Q3 Launch", "release", null))
                .thenReturn(milestoneResponse(milestoneId, "Q3 Launch"));
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));

        CandidateMilestone milestone = new CandidateMilestone("m1", "Q3 Launch", "release", null);
        CandidateEpicProposal candidate = epic("Bulk Import", null, List.of(), null, "m1");

        MaterializationSummary summary =
                materialize(materializer, runId, document(List.of(milestone), List.of(candidate), null));

        assertThat(summary.createdMilestoneIds()).containsExactly(milestoneId);
        verify(internalRunService)
                .createEpic(
                        eq(runId),
                        eq(new InternalCreateEpicRequest("Bulk Import", "d", "m", Priority.medium, milestoneId)));
    }

    @Test
    void milestone_dedupedByFindOrCreate_reusedAcrossEpics() {
        UUID milestoneId = UUID.randomUUID();
        when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(softwareProjectId);
        when(milestoneService.findOrCreate(eq(softwareProjectId), eq("Q3 Launch"), any(), any()))
                .thenReturn(milestoneResponse(milestoneId, "Q3 Launch"));
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(UUID.randomUUID()));

        CandidateMilestone milestone = new CandidateMilestone("m1", "Q3 Launch", null, null);
        CandidateEpicProposal candidateA = epic("Epic A", null, List.of(), "a", "m1");
        CandidateEpicProposal candidateB = epic("Epic B", null, List.of(), "b", "m1");

        MaterializationSummary summary =
                materialize(materializer, runId, document(List.of(milestone), List.of(candidateA, candidateB), null));

        // findOrCreate is the dedup point (a Mockito stub, not a real DB), so this only verifies
        // the materializer calls findOrCreate once per candidate Milestone declaration, not once
        // per referencing Epic — the real dedup-by-name guarantee lives in
        // DefaultMilestoneServiceTest / the find-or-create service itself.
        verify(milestoneService, times(1)).findOrCreate(eq(softwareProjectId), eq("Q3 Launch"), any(), any());
        assertThat(summary.createdMilestoneIds()).containsExactly(milestoneId);
        // Epic-side reuse: BOTH referencing Epics are created carrying the shared milestoneId, not
        // just the single findOrCreate call above — otherwise a regression that dropped the
        // milestone on the second Epic would still pass the count/times assertions.
        verify(internalRunService, times(2))
                .createEpic(eq(runId), argThat(req -> req != null && milestoneId.equals(req.milestoneId())));
    }

    @Test
    void milestone_findOrCreateRaceLoss_retriedAndEpicStillGetsMilestoneId() {
        UUID epicId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(softwareProjectId);
        // Simulates two concurrent gate approvals racing findOrCreate's find-then-save for the same
        // project/name: this call's own save loses the unique-name race and throws, then a retry
        // (a fresh call through the same mock, standing in for a fresh transaction) finds the
        // winner's now-committed row.
        when(milestoneService.findOrCreate(softwareProjectId, "Q3 Launch", "release", null))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key value"))
                .thenReturn(milestoneResponse(milestoneId, "Q3 Launch"));
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));

        CandidateMilestone milestone = new CandidateMilestone("m1", "Q3 Launch", "release", null);
        CandidateEpicProposal candidate = epic("Bulk Import", null, List.of(), null, "m1");

        MaterializationSummary summary =
                materialize(materializer, runId, document(List.of(milestone), List.of(candidate), null));

        // The race is invisible to the caller: no error recorded, the Milestone counts as created
        // once, and the Epic still carries its milestoneId rather than materializing with null.
        assertThat(summary.errors()).isEmpty();
        assertThat(summary.createdMilestoneIds()).containsExactly(milestoneId);
        verify(milestoneService, times(2)).findOrCreate(eq(softwareProjectId), eq("Q3 Launch"), eq("release"), any());
        verify(internalRunService)
                .createEpic(
                        eq(runId),
                        eq(new InternalCreateEpicRequest("Bulk Import", "d", "m", Priority.medium, milestoneId)));
    }

    @Test
    void milestone_findOrCreateRaceLossTwice_recordedAsError_doesNotAbortBatch() {
        when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(softwareProjectId);
        // Both attempts lose the race (or hit a genuine, persistent constraint failure): the retry
        // budget is exhausted and the failure degrades to the same best-effort error recording as
        // every other candidate-materialization failure, rather than retrying forever.
        when(milestoneService.findOrCreate(softwareProjectId, "Q3 Launch", "release", null))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key value"));
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(UUID.randomUUID()));

        CandidateMilestone milestone = new CandidateMilestone("m1", "Q3 Launch", "release", null);
        CandidateEpicProposal candidate = epic("Bulk Import", null, List.of(), null, "m1");

        MaterializationSummary summary =
                materialize(materializer, runId, document(List.of(milestone), List.of(candidate), null));

        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0)).contains("Q3 Launch");
        assertThat(summary.createdMilestoneIds()).isEmpty();
        verify(milestoneService, times(2)).findOrCreate(eq(softwareProjectId), eq("Q3 Launch"), eq("release"), any());
        // Best-effort: the Epic itself still materializes, just without the (dropped) milestoneId.
        verify(internalRunService).createEpic(eq(runId), argThat(req -> req != null && req.milestoneId() == null));
    }

    @Test
    void dependencyEdge_created_countedInSummary() {
        UUID epicAId = UUID.randomUUID();
        UUID epicBId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any()))
                .thenReturn(epicResponse(epicAId))
                .thenReturn(epicResponse(epicBId));
        when(internalRunService.createDependency(eq(runId), any()))
                .thenReturn(
                        new DependencyEdgeResponse(UUID.randomUUID(), "epic", epicAId, "epic", epicBId, Instant.now()));

        CandidateEpicProposal candidateA = epic("Epic A", null, List.of(), "a", null);
        CandidateEpicProposal candidateB = epic("Epic B", null, List.of(), "b", null);
        CandidateDependency dep = new CandidateDependency("a", "b");

        MaterializationSummary summary =
                materialize(materializer, runId, document(null, List.of(candidateA, candidateB), List.of(dep)));

        assertThat(summary.createdDependencyCount()).isEqualTo(1);
        assertThat(summary.errors()).isEmpty();
        verify(internalRunService)
                .createDependency(eq(runId), eq(new InternalCreateDependencyRequest("epic", epicAId, "epic", epicBId)));
    }

    @Test
    void dependencyEdge_cyclic_recordedInErrors_doesNotAbortBatch() {
        UUID epicAId = UUID.randomUUID();
        UUID epicBId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any()))
                .thenReturn(epicResponse(epicAId))
                .thenReturn(epicResponse(epicBId));
        when(internalRunService.createDependency(eq(runId), any()))
                .thenThrow(new DependencyCycleException(epicAId, epicBId));

        CandidateEpicProposal candidateA = epic("Epic A", null, List.of(), "a", null);
        CandidateEpicProposal candidateB = epic("Epic B", null, List.of(), "b", null);
        CandidateDependency dep = new CandidateDependency("a", "b");

        MaterializationSummary summary =
                materialize(materializer, runId, document(null, List.of(candidateA, candidateB), List.of(dep)));

        // The batch is not aborted: both Epics are still recorded as created.
        assertThat(summary.createdEpicIds()).containsExactlyInAnyOrder(epicAId, epicBId);
        assertThat(summary.createdDependencyCount()).isZero();
        assertThat(summary.errors()).hasSize(1);
    }

    @Test
    void dependencyEdge_unresolvedKey_skippedAndRecorded() {
        UUID epicAId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicAId));

        CandidateEpicProposal candidateA = epic("Epic A", null, List.of(), "a", null);
        CandidateDependency dep = new CandidateDependency("a", "does-not-exist");

        MaterializationSummary summary =
                materialize(materializer, runId, document(null, List.of(candidateA), List.of(dep)));

        assertThat(summary.createdDependencyCount()).isZero();
        assertThat(summary.errors()).hasSize(1);
        verify(internalRunService, never()).createDependency(any(), any());
    }

    @Test
    void oneFailingCandidate_doesNotBlockTheRest() {
        UUID goodEpicId = UUID.randomUUID();
        CandidateEpicProposal failing = epic("Bad", null, List.of(), null, null);
        CandidateEpicProposal good = epic("Good", null, List.of(), null, null);

        // Both candidates have a null priority string, which parses to the Priority.medium default,
        // so the materializer forwards the 5-arg request carrying Priority.medium.
        when(internalRunService.createEpic(
                        eq(runId), eq(new InternalCreateEpicRequest("Bad", "d", "m", Priority.medium, null))))
                .thenThrow(new RuntimeException("boom"));
        when(internalRunService.createEpic(
                        eq(runId), eq(new InternalCreateEpicRequest("Good", "d", "m", Priority.medium, null))))
                .thenReturn(epicResponse(goodEpicId));

        MaterializationSummary summary = materialize(materializer, runId, document(null, List.of(failing, good), null));

        assertThat(summary.createdEpicIds()).containsExactly(goodEpicId);
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0)).contains("Bad").contains("boom");
    }

    @Test
    void nullEpicCandidate_recordedInErrors_doesNotAbortBatch() {
        // Regression test: SignalRequest.editedCandidates carries `@Valid` on the epics list, but
        // Bean Validation's cascade skips (does not reject) a `null` element inside a collection —
        // so a document like {"epics":[null,{...}]} reaches the materializer un-guarded. A null
        // candidate must be caught and recorded like any other per-candidate failure,
        // not thrown as an uncaught NPE that aborts the whole batch.
        UUID goodEpicId = UUID.randomUUID();
        CandidateEpicProposal good = epic("Good", null, List.of(), null, null);
        when(internalRunService.createEpic(
                        eq(runId), eq(new InternalCreateEpicRequest("Good", "d", "m", Priority.medium, null))))
                .thenReturn(epicResponse(goodEpicId));

        List<CandidateEpicProposal> epicsWithNull = new java.util.ArrayList<>();
        epicsWithNull.add(null);
        epicsWithNull.add(good);

        MaterializationSummary summary = materialize(materializer, runId, document(null, epicsWithNull, null));

        assertThat(summary.createdEpicIds()).containsExactly(goodEpicId);
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0)).contains("<null>");
    }

    @Test
    void storyFailsAfterEpicCreated_epicStillRecordedAsCreated_storyFailureReportedSeparately() {
        UUID epicId = UUID.randomUUID();
        UUID goodStoryId = UUID.randomUUID();
        CandidateStoryProposal badStory = story("Bad Story", List.of(), null, null);
        CandidateStoryProposal goodStory = story("Good Story", List.of(), null, null);
        CandidateEpicProposal candidate = epic("Bulk Import", null, List.of(badStory, goodStory), null, null);

        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(
                        eq(runId),
                        eq(epicId),
                        eq(new InternalCreateStoryRequest("Bad Story", "s-desc", Priority.medium))))
                .thenThrow(new RuntimeException("story boom"));
        when(internalRunService.createStory(
                        eq(runId),
                        eq(epicId),
                        eq(new InternalCreateStoryRequest("Good Story", "s-desc", Priority.medium))))
                .thenReturn(storyResponse(goodStoryId, epicId));

        MaterializationSummary summary = materialize(materializer, runId, document(null, List.of(candidate), null));

        // The Epic row was actually committed by createEpic() before the Story failure, so it must
        // be recorded as created — otherwise the summary would tell the reviewer this candidate was
        // entirely skipped while an orphaned, untracked Epic silently exists in the database.
        assertThat(summary.createdEpicIds()).containsExactly(epicId);
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0))
                .contains("Bad Story")
                .contains("Bulk Import")
                .contains("story boom");
        verify(internalRunService)
                .createStory(
                        eq(runId),
                        eq(epicId),
                        eq(new InternalCreateStoryRequest("Good Story", "s-desc", Priority.medium)));
    }

    @Test
    void emptyDocument_producesEmptySummary() {
        MaterializationSummary summary = materialize(materializer, runId, document(List.of(), List.of(), List.of()));

        assertThat(summary.createdEpicIds()).isEmpty();
        assertThat(summary.createdMilestoneIds()).isEmpty();
        assertThat(summary.createdDependencyCount()).isZero();
        assertThat(summary.errors()).isEmpty();
        verifyNoInteractions(internalRunService, milestoneService, gitHubAppService);
    }

    @Test
    void nullDocument_producesEmptySummary() {
        MaterializationSummary summary = materialize(materializer, runId, null);

        assertThat(summary.createdEpicIds()).isEmpty();
        assertThat(summary.createdMilestoneIds()).isEmpty();
        assertThat(summary.createdDependencyCount()).isZero();
        assertThat(summary.errors()).isEmpty();
        verifyNoInteractions(internalRunService, milestoneService, gitHubAppService);
    }

    @Test
    void candidatePriority_lowercase_isParsedOntoEnum() {
        assertMaterializedEpicPriority("high", Priority.high);
    }

    @Test
    void candidatePriority_mixedCase_isParsedOntoEnum() {
        assertMaterializedEpicPriority("LoW", Priority.low);
    }

    @Test
    void candidatePriority_null_defaultsToMedium() {
        assertMaterializedEpicPriority(null, Priority.medium);
    }

    @Test
    void candidatePriority_blank_defaultsToMedium() {
        assertMaterializedEpicPriority("   ", Priority.medium);
    }

    @Test
    void candidatePriority_unrecognized_defaultsToMedium() {
        assertMaterializedEpicPriority("urgent", Priority.medium);
    }

    /**
     * Materializes a single story-less candidate whose {@code priority} string is {@code
     * candidatePriority}, and asserts the Epic forwarded to {@link InternalRunService#createEpic}
     * carries {@code expected} as its parsed {@link Priority}.
     */
    private void assertMaterializedEpicPriority(String candidatePriority, Priority expected) {
        UUID epicId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));

        CandidateEpicProposal candidate = epic("Epic", candidatePriority, List.of(), null, null);

        materialize(materializer, runId, document(null, List.of(candidate), null));

        verify(internalRunService)
                .createEpic(eq(runId), eq(new InternalCreateEpicRequest("Epic", "d", "m", expected, null)));
    }

    // -----------------------------------------------------------------------
    // Anchors
    // -----------------------------------------------------------------------

    @Test
    void epicAnchor_neverCreated_childrenAttachUnderIt() {
        UUID anchorEpicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        when(internalRunService.createStory(eq(runId), eq(anchorEpicId), any()))
                .thenReturn(storyResponse(storyId, anchorEpicId));

        CandidateEpicProposal candidate =
                epicAnchor(anchorEpicId, List.of(story("New Story", List.of(), null, null)), "epic-key");

        MaterializationSummary summary = materialize(materializer, runId, document(null, List.of(candidate), null));

        assertThat(summary.createdEpicIds()).isEmpty();
        assertThat(summary.createdStoryIds()).containsExactly(storyId);
        verify(internalRunService, never()).createEpic(any(), any());
        verify(internalRunService).createStory(eq(runId), eq(anchorEpicId), any());
    }

    @Test
    void storyAnchor_neverCreated_newTasksAttachUnderIt() {
        UUID anchorEpicId = UUID.randomUUID();
        UUID anchorStoryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(internalRunService.createTask(eq(runId), eq(anchorEpicId), eq(anchorStoryId), any()))
                .thenReturn(taskResponse(taskId, anchorStoryId, "New Task", "t-desc"));

        CandidateStoryProposal storyEntry =
                storyAnchor(anchorStoryId, List.of(task("New Task", null, null)), "story-key");
        CandidateEpicProposal candidate = epicAnchor(anchorEpicId, List.of(storyEntry), "epic-key");

        MaterializationSummary summary = materialize(materializer, runId, document(null, List.of(candidate), null));

        assertThat(summary.createdStoryIds()).isEmpty();
        assertThat(summary.createdTaskIds()).containsExactly(taskId);
        verify(internalRunService, never()).createStory(any(), any(), any());
        verify(internalRunService).createTask(eq(runId), eq(anchorEpicId), eq(anchorStoryId), any());
    }

    @Test
    void taskAnchor_neverCreated_onlyKeyRegistered() {
        UUID anchorEpicId = UUID.randomUUID();
        UUID anchorStoryId = UUID.randomUUID();
        UUID anchorTaskId = UUID.randomUUID();

        CandidateStoryProposal storyEntry =
                storyAnchor(anchorStoryId, List.of(taskAnchor(anchorTaskId, "task-key")), null);
        CandidateEpicProposal candidate = epicAnchor(anchorEpicId, List.of(storyEntry), null);

        MaterializationSummary summary = materialize(materializer, runId, document(null, List.of(candidate), null));

        assertThat(summary.createdTaskIds()).isEmpty();
        verify(internalRunService, never()).createTask(any(), any(), any(), any());
    }

    @Test
    void dependencyBetweenAnchorAndNewItem_usesAnchorsExistingId() {
        UUID anchorTaskId = UUID.randomUUID();
        UUID anchorEpicId = UUID.randomUUID();
        UUID anchorStoryId = UUID.randomUUID();
        UUID newEpicId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(newEpicId));
        when(internalRunService.createDependency(eq(runId), any()))
                .thenReturn(new DependencyEdgeResponse(
                        UUID.randomUUID(), "task", anchorTaskId, "epic", newEpicId, Instant.now()));

        CandidateStoryProposal storyEntry =
                storyAnchor(anchorStoryId, List.of(taskAnchor(anchorTaskId, "existing-task")), null);
        CandidateEpicProposal anchorEpic = epicAnchor(anchorEpicId, List.of(storyEntry), null);
        CandidateEpicProposal newEpic = epic("New Epic", null, List.of(), "new-epic", null);
        CandidateDependency dep = new CandidateDependency("existing-task", "new-epic");

        MaterializationSummary summary =
                materialize(materializer, runId, document(null, List.of(anchorEpic, newEpic), List.of(dep)));

        assertThat(summary.createdDependencyCount()).isEqualTo(1);
        verify(internalRunService)
                .createDependency(
                        eq(runId), eq(new InternalCreateDependencyRequest("task", anchorTaskId, "epic", newEpicId)));
    }

    // -----------------------------------------------------------------------
    // GitHub issue linkage (roadmap_extension mode only)
    // -----------------------------------------------------------------------

    @Test
    void extensionMode_newTask_filesIssueAndPersistsLinkage() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID gitRepoId = UUID.randomUUID();
        GitRepo repo = new GitRepo();
        repo.setId(gitRepoId);
        repo.setUrl("https://github.com/acme/widgets");

        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(eq(runId), eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(internalRunService.createTask(eq(runId), eq(epicId), eq(storyId), any()))
                .thenReturn(taskResponse(taskId, storyId, "Task 1", "t-desc"));
        when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(softwareProjectId);
        when(internalRunService.resolveRepos(softwareProjectId)).thenReturn(List.of(repo));
        when(gitHubCredentialResolver.getTokenForRepo(gitRepoId)).thenReturn("tok");
        when(gitHubAppService.createIssue(eq("tok"), eq("acme/widgets"), eq("Task 1"), eq("t-desc")))
                .thenReturn(new GitHubAppService.CreatedIssue(42, "https://github.com/acme/widgets/issues/42"));

        CandidateEpicProposal candidate = epic(
                "Bulk Import",
                null,
                List.of(story("Story 1", List.of(task("Task 1", null, null)), null, null)),
                null,
                null);

        MaterializationSummary summary = materializer.materialize(
                runId, document(null, List.of(candidate), null), RoadmapMaterializeMode.roadmap_extension);

        assertThat(summary.errors()).isEmpty();
        ArgumentCaptor<com.choruskube.core.model.TaskGithubIssue> captor =
                ArgumentCaptor.forClass(com.choruskube.core.model.TaskGithubIssue.class);
        verify(taskGithubIssueRepository).save(captor.capture());
        assertThat(captor.getValue().getTaskId()).isEqualTo(taskId);
        assertThat(captor.getValue().getGitRepoId()).isEqualTo(gitRepoId);
        assertThat(captor.getValue().getIssueNumber()).isEqualTo(42);
    }

    @Test
    void extensionMode_issueFilingFails_taskStaysCreated_failureRecorded() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(eq(runId), eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(internalRunService.createTask(eq(runId), eq(epicId), eq(storyId), any()))
                .thenReturn(taskResponse(taskId, storyId, "Task 1", "t-desc"));
        when(internalRunService.resolveSoftwareProjectId(runId)).thenThrow(new RuntimeException("no project"));

        CandidateEpicProposal candidate = epic(
                "Bulk Import",
                null,
                List.of(story("Story 1", List.of(task("Task 1", null, null)), null, null)),
                null,
                null);

        MaterializationSummary summary = materializer.materialize(
                runId, document(null, List.of(candidate), null), RoadmapMaterializeMode.roadmap_extension);

        assertThat(summary.createdTaskIds()).containsExactly(taskId);
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0)).contains("Task 1");
        verifyNoInteractions(taskGithubIssueRepository);
    }

    @Test
    void extensionMode_multiRepoProject_filesIssueInTheTasksRepoId() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        GitRepo backend = repo("https://github.com/acme/backend");
        GitRepo frontend = repo("https://github.com/acme/frontend");
        stubOneNewTask(epicId, storyId, taskId);
        when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(softwareProjectId);
        when(internalRunService.resolveRepos(softwareProjectId)).thenReturn(List.of(backend, frontend));
        when(gitHubCredentialResolver.getTokenForRepo(frontend.getId())).thenReturn("tok");
        when(gitHubAppService.createIssue(eq("tok"), eq("acme/frontend"), eq("Task 1"), eq("t-desc")))
                .thenReturn(new GitHubAppService.CreatedIssue(7, "https://github.com/acme/frontend/issues/7"));

        CandidateEpicProposal candidate = epic(
                "Bulk Import",
                null,
                List.of(story("Story 1", List.of(task("Task 1", null, null, frontend.getId())), null, null)),
                null,
                null);

        MaterializationSummary summary = materializer.materialize(
                runId, document(null, List.of(candidate), null), RoadmapMaterializeMode.roadmap_extension);

        assertThat(summary.errors()).isEmpty();
        ArgumentCaptor<com.choruskube.core.model.TaskGithubIssue> captor =
                ArgumentCaptor.forClass(com.choruskube.core.model.TaskGithubIssue.class);
        verify(taskGithubIssueRepository).save(captor.capture());
        assertThat(captor.getValue().getGitRepoId()).isEqualTo(frontend.getId());
        assertThat(captor.getValue().getIssueUrl()).isEqualTo("https://github.com/acme/frontend/issues/7");
    }

    @Test
    void extensionMode_multiRepoProject_noRepoId_taskStaysCreated_noIssueFiled() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        stubOneNewTask(epicId, storyId, taskId);
        when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(softwareProjectId);
        when(internalRunService.resolveRepos(softwareProjectId))
                .thenReturn(List.of(repo("https://github.com/acme/backend"), repo("https://github.com/acme/frontend")));

        CandidateEpicProposal candidate = epic(
                "Bulk Import",
                null,
                List.of(story("Story 1", List.of(task("Task 1", null, null)), null, null)),
                null,
                null);

        MaterializationSummary summary = materializer.materialize(
                runId, document(null, List.of(candidate), null), RoadmapMaterializeMode.roadmap_extension);

        assertThat(summary.createdTaskIds()).containsExactly(taskId);
        assertThat(summary.errors()).singleElement().asString().contains("no repoId was given");
        verifyNoInteractions(gitHubAppService, taskGithubIssueRepository);
    }

    private void stubOneNewTask(UUID epicId, UUID storyId, UUID taskId) {
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(eq(runId), eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(internalRunService.createTask(eq(runId), eq(epicId), eq(storyId), any()))
                .thenReturn(taskResponse(taskId, storyId, "Task 1", "t-desc"));
    }

    private static GitRepo repo(String url) {
        GitRepo repo = new GitRepo();
        repo.setId(UUID.randomUUID());
        repo.setUrl(url);
        return repo;
    }

    @Test
    void candidatesMode_neverFilesIssues() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(eq(runId), eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(internalRunService.createTask(eq(runId), eq(epicId), eq(storyId), any()))
                .thenReturn(taskResponse(taskId, storyId, "Task 1", "t-desc"));

        CandidateEpicProposal candidate = epic(
                "Bulk Import",
                null,
                List.of(story("Story 1", List.of(task("Task 1", null, null)), null, null)),
                null,
                null);

        materialize(materializer, runId, document(null, List.of(candidate), null));

        verifyNoInteractions(gitHubAppService, gitHubCredentialResolver, taskGithubIssueRepository);
    }

    @Test
    void writerEntry_writesOnlyThroughTheWriter_andNeverFilesGithubIssues() {
        RoadmapItemWriter writer = Mockito.mock(RoadmapItemWriter.class);
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        when(writer.softwareProjectId()).thenReturn(softwareProjectId);
        when(milestoneService.findOrCreate(eq(softwareProjectId), eq("Q4"), any(), any()))
                .thenReturn(milestoneResponse(milestoneId, "Q4"));
        when(writer.createEpic(any())).thenReturn(epicResponse(epicId));
        when(writer.createStory(eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(writer.createTask(eq(epicId), eq(storyId), any())).thenReturn(taskResponse(taskId, storyId, "T", "d"));

        MaterializationSummary summary = materializer.materialize(
                writer,
                document(
                        List.of(new CandidateMilestone("m1", "Q4", null, null)),
                        List.of(epic(
                                "E",
                                "High",
                                List.of(story("S", List.of(task("T", "t", null)), null, null)),
                                "e",
                                "m1")),
                        List.of(new CandidateDependency("e", "t"))));

        assertThat(summary.errors()).isEmpty();
        assertThat(summary.createdEpicIds()).containsExactly(epicId);
        assertThat(summary.createdTaskIds()).containsExactly(taskId);
        assertThat(summary.createdDependencyCount()).isEqualTo(1);
        ArgumentCaptor<InternalCreateEpicRequest> epicReq = ArgumentCaptor.forClass(InternalCreateEpicRequest.class);
        verify(writer).createEpic(epicReq.capture());
        assertThat(epicReq.getValue().milestoneId()).isEqualTo(milestoneId);
        verify(writer).createDependency(new InternalCreateDependencyRequest("epic", epicId, "task", taskId));
        verifyNoInteractions(internalRunService, gitHubAppService, taskGithubIssueRepository);
    }
}
