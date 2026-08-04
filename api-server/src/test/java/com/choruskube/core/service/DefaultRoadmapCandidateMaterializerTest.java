package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link DefaultRoadmapCandidateMaterializer}: creates each candidate Epic/Story/Task through
 * {@link InternalRunService}'s existing agent-facing write path (Decision 3), best-effort per
 * top-level candidate (Caveat 3), and never forwards {@code repos}/{@code priority} (Caveat 6).
 */
class DefaultRoadmapCandidateMaterializerTest {

    private InternalRunService internalRunService;
    private DefaultRoadmapCandidateMaterializer materializer;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        internalRunService = Mockito.mock(InternalRunService.class);
        materializer = new DefaultRoadmapCandidateMaterializer(internalRunService);
    }

    private static EpicResponse epicResponse(UUID id) {
        return new EpicResponse(id, "t", "d", "m", "open", "active", null, null, null, Instant.now(), Instant.now(), 0);
    }

    private static StoryResponse storyResponse(UUID id, UUID epicId) {
        return new StoryResponse(id, epicId, "t", "d", "open", "backlog", null, null, Instant.now(), Instant.now());
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
                Instant.now());
    }

    @Test
    void materializesFullEpicStoryTaskShape_inOrder() {
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(eq(runId), eq(epicId), any())).thenReturn(storyResponse(storyId, epicId));
        when(internalRunService.createTask(eq(runId), eq(epicId), eq(storyId), any()))
                .thenReturn(taskResponse(storyId));

        CandidateEpicProposal candidate = new CandidateEpicProposal(
                "Bulk Import",
                "desc",
                "why",
                List.of("repo-a", "repo-b"),
                "High",
                List.of(new CandidateStoryProposal(
                        "Story 1", "s-desc", List.of(new CandidateTaskProposal("Task 1", "t-desc")))));

        MaterializationSummary summary = materializer.materialize(runId, List.of(candidate));

        assertThat(summary.createdEpicIds()).containsExactly(epicId);
        assertThat(summary.errors()).isEmpty();

        verify(internalRunService)
                .createEpic(eq(runId), eq(new InternalCreateEpicRequest("Bulk Import", "desc", "why")));
        verify(internalRunService)
                .createStory(eq(runId), eq(epicId), eq(new InternalCreateStoryRequest("Story 1", "s-desc")));
        verify(internalRunService)
                .createTask(eq(runId), eq(epicId), eq(storyId), eq(new InternalCreateTaskRequest("Task 1", "t-desc")));
    }

    @Test
    void oneFailingCandidate_doesNotBlockTheRest() {
        UUID goodEpicId = UUID.randomUUID();
        CandidateEpicProposal failing = new CandidateEpicProposal("Bad", "d", "m", null, null, List.of());
        CandidateEpicProposal good = new CandidateEpicProposal("Good", "d", "m", null, null, List.of());

        when(internalRunService.createEpic(eq(runId), eq(new InternalCreateEpicRequest("Bad", "d", "m"))))
                .thenThrow(new RuntimeException("boom"));
        when(internalRunService.createEpic(eq(runId), eq(new InternalCreateEpicRequest("Good", "d", "m"))))
                .thenReturn(epicResponse(goodEpicId));

        MaterializationSummary summary = materializer.materialize(runId, List.of(failing, good));

        assertThat(summary.createdEpicIds()).containsExactly(goodEpicId);
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0)).contains("Bad").contains("boom");
    }

    @Test
    void storyFailsAfterEpicCreated_epicStillRecordedAsCreated_storyFailureReportedSeparately() {
        UUID epicId = UUID.randomUUID();
        UUID goodStoryId = UUID.randomUUID();
        CandidateStoryProposal badStory = new CandidateStoryProposal("Bad Story", "d", List.of());
        CandidateStoryProposal goodStory = new CandidateStoryProposal("Good Story", "d", List.of());
        CandidateEpicProposal candidate =
                new CandidateEpicProposal("Bulk Import", "d", "m", null, null, List.of(badStory, goodStory));

        when(internalRunService.createEpic(eq(runId), any())).thenReturn(epicResponse(epicId));
        when(internalRunService.createStory(
                        eq(runId), eq(epicId), eq(new InternalCreateStoryRequest("Bad Story", "d"))))
                .thenThrow(new RuntimeException("story boom"));
        when(internalRunService.createStory(
                        eq(runId), eq(epicId), eq(new InternalCreateStoryRequest("Good Story", "d"))))
                .thenReturn(storyResponse(goodStoryId, epicId));

        MaterializationSummary summary = materializer.materialize(runId, List.of(candidate));

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
                .createStory(eq(runId), eq(epicId), eq(new InternalCreateStoryRequest("Good Story", "d")));
    }

    @Test
    void emptyCandidateList_producesEmptySummary() {
        MaterializationSummary summary = materializer.materialize(runId, List.of());

        assertThat(summary.createdEpicIds()).isEmpty();
        assertThat(summary.errors()).isEmpty();
        verifyNoInteractions(internalRunService);
    }

    @Test
    void nullCandidateList_producesEmptySummary() {
        MaterializationSummary summary = materializer.materialize(runId, null);

        assertThat(summary.createdEpicIds()).isEmpty();
        assertThat(summary.errors()).isEmpty();
        verifyNoInteractions(internalRunService);
    }
}
