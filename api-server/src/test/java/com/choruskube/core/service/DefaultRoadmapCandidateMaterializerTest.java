package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateMilestone;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.DependencyCycleException;
import com.choruskube.core.model.enums.Priority;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link DefaultRoadmapCandidateMaterializer}: creates each candidate Milestone/Epic/Story/Task
 * through {@link InternalRunService}/{@link MilestoneService}'s existing agent-facing write paths
 *, then each candidate dependency edge directly through {@link
 * WorkItemDependencyService}, best-effort per top-level candidate/edge. A
 * candidate item's free-text {@code priority} is parsed case-insensitively onto {@link Priority}
 * (defaulting to {@link Priority#medium}); Epic {@code repos} is still dropped.
 */
class DefaultRoadmapCandidateMaterializerTest {

    private InternalRunService internalRunService;
    private MilestoneService milestoneService;
    private WorkItemDependencyService workItemDependencyService;
    private DefaultRoadmapCandidateMaterializer materializer;
    private final UUID runId = UUID.randomUUID();
    private final UUID softwareProjectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        internalRunService = Mockito.mock(InternalRunService.class);
        milestoneService = Mockito.mock(MilestoneService.class);
        workItemDependencyService = Mockito.mock(WorkItemDependencyService.class);
        materializer = new DefaultRoadmapCandidateMaterializer(
                internalRunService, milestoneService, workItemDependencyService);
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
        return new TaskResponse(
                UUID.randomUUID(),
                storyId,
                "t",
                "d",
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
        return new CandidateEpicProposal(title, "d", "m", null, priority, stories, key, milestoneKey);
    }

    private static CandidateStoryProposal story(
            String title, List<CandidateTaskProposal> tasks, String key, String priority) {
        return new CandidateStoryProposal(title, "s-desc", tasks, key, priority);
    }

    private static CandidateTaskProposal task(String title, String key, String priority) {
        return new CandidateTaskProposal(title, "t-desc", key, priority);
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

        MaterializationSummary summary = materializer.materialize(runId, document(null, List.of(candidate), null));

        assertThat(summary.createdEpicIds()).containsExactly(epicId);
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

        materializer.materialize(runId, document(null, List.of(candidate), null));

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
                materializer.materialize(runId, document(List.of(milestone), List.of(candidate), null));

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
                materializer.materialize(runId, document(List.of(milestone), List.of(candidateA, candidateB), null));

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
    void dependencyEdge_created_countedInSummary() {
        UUID epicAId = UUID.randomUUID();
        UUID epicBId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any()))
                .thenReturn(epicResponse(epicAId))
                .thenReturn(epicResponse(epicBId));

        CandidateEpicProposal candidateA = epic("Epic A", null, List.of(), "a", null);
        CandidateEpicProposal candidateB = epic("Epic B", null, List.of(), "b", null);
        CandidateDependency dep = new CandidateDependency("a", "b");

        MaterializationSummary summary =
                materializer.materialize(runId, document(null, List.of(candidateA, candidateB), List.of(dep)));

        assertThat(summary.createdDependencyCount()).isEqualTo(1);
        assertThat(summary.errors()).isEmpty();
        verify(workItemDependencyService).create(eq(new CreateDependencyRequest("epic", epicAId, "epic", epicBId)));
    }

    @Test
    void dependencyEdge_cyclic_recordedInErrors_doesNotAbortBatch() {
        UUID epicAId = UUID.randomUUID();
        UUID epicBId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any()))
                .thenReturn(epicResponse(epicAId))
                .thenReturn(epicResponse(epicBId));
        when(workItemDependencyService.create(any())).thenThrow(new DependencyCycleException(epicAId, epicBId));

        CandidateEpicProposal candidateA = epic("Epic A", null, List.of(), "a", null);
        CandidateEpicProposal candidateB = epic("Epic B", null, List.of(), "b", null);
        CandidateDependency dep = new CandidateDependency("a", "b");

        MaterializationSummary summary =
                materializer.materialize(runId, document(null, List.of(candidateA, candidateB), List.of(dep)));

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
                materializer.materialize(runId, document(null, List.of(candidateA), List.of(dep)));

        assertThat(summary.createdDependencyCount()).isZero();
        assertThat(summary.errors()).hasSize(1);
        verifyNoInteractions(workItemDependencyService);
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

        MaterializationSummary summary = materializer.materialize(runId, document(null, List.of(failing, good), null));

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

        MaterializationSummary summary = materializer.materialize(runId, document(null, epicsWithNull, null));

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

        MaterializationSummary summary = materializer.materialize(runId, document(null, List.of(candidate), null));

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
        MaterializationSummary summary = materializer.materialize(runId, document(List.of(), List.of(), List.of()));

        assertThat(summary.createdEpicIds()).isEmpty();
        assertThat(summary.createdMilestoneIds()).isEmpty();
        assertThat(summary.createdDependencyCount()).isZero();
        assertThat(summary.errors()).isEmpty();
        verifyNoInteractions(internalRunService, milestoneService, workItemDependencyService);
    }

    @Test
    void nullDocument_producesEmptySummary() {
        MaterializationSummary summary = materializer.materialize(runId, null);

        assertThat(summary.createdEpicIds()).isEmpty();
        assertThat(summary.createdMilestoneIds()).isEmpty();
        assertThat(summary.createdDependencyCount()).isZero();
        assertThat(summary.errors()).isEmpty();
        verifyNoInteractions(internalRunService, milestoneService, workItemDependencyService);
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

        materializer.materialize(runId, document(null, List.of(candidate), null));

        verify(internalRunService)
                .createEpic(eq(runId), eq(new InternalCreateEpicRequest("Epic", "d", "m", expected, null)));
    }
}
