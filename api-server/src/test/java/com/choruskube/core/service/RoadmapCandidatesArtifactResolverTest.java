package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers {@link RoadmapCandidatesArtifactResolver}: the Bean Validation guardrail that an
 * AI-authored {@code roadmap_candidates.json} artifact must satisfy the identical {@code
 * @NotBlank}/{@code @Size} constraint tree that {@code SignalRequest.editedCandidates} enforces on
 * the reviewer-edited path (not just be well-formed JSON), plus the document-shape parsing,
 * legacy bare-array back-compat, and the key/reference/cycle validation added on top
 * of Bean Validation.
 */
class RoadmapCandidatesArtifactResolverTest {

    private ArtifactResolutionService artifactResolutionService;
    private ArtifactService artifactService;
    private RoadmapAnchorLookup anchorLookup;
    private InternalRunService internalRunService;
    private NodeExecutionRepository nodeExecutionRepository;
    private RoadmapCandidatesArtifactResolver resolver;

    private final UUID runId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private final UUID analyzerExecId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        artifactResolutionService = Mockito.mock(ArtifactResolutionService.class);
        artifactService = Mockito.mock(ArtifactService.class);
        anchorLookup = Mockito.mock(RoadmapAnchorLookup.class);
        internalRunService = Mockito.mock(InternalRunService.class);
        nodeExecutionRepository = Mockito.mock(NodeExecutionRepository.class);
        resolver = new RoadmapCandidatesArtifactResolver(
                artifactResolutionService,
                artifactService,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Validation.buildDefaultValidatorFactory().getValidator(),
                anchorLookup,
                internalRunService,
                nodeExecutionRepository);
    }

    private List<ResolvedArtifactGroup> requiredArtifacts() {
        return List.of(new ResolvedArtifactGroup(
                analyzerExecId,
                "roadmap_analyzer",
                List.of(new ResolvedArtifactEntry("roadmap_candidates.json", "Structured breakdown", false))));
    }

    private void stubArtifactContent(String json) {
        Mockito.when(artifactResolutionService.resolveRequiredArtifacts(templateNodeId, runId))
                .thenReturn(requiredArtifacts());
        Mockito.when(artifactService.getArtifactContent(runId, analyzerExecId, "roadmap_candidates.json"))
                .thenReturn(json);
    }

    @Test
    void wellFormedAndValidDocument_resolvesCandidates() {
        stubArtifactContent("""
                {"epics":[
                  {"title":"Bulk Import","description":"desc","motivation":"why",
                   "stories":[{"title":"Story 1","description":"s","tasks":[{"title":"Task 1","description":"t"}]}]}
                ]}
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.epics().get(0).title()).isEqualTo("Bulk Import");
    }

    @Test
    void legacyBareArray_wrappedAsEpicsOnly_resolves() {
        stubArtifactContent("""
                [
                  {"title":"Bulk Import","description":"desc","motivation":"why",
                   "stories":[{"title":"Story 1","description":"s","tasks":[{"title":"Task 1","description":"t"}]}]}
                ]
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        // The resolver normalizes absent lists to empty (not null) once it runs the post-Bean-
        // Validation reference/cycle pass — see validateReferencesAndCycles.
        assertThat(result.milestones()).isEmpty();
        assertThat(result.dependencies()).isEmpty();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.epics().get(0).title()).isEqualTo("Bulk Import");
    }

    @Test
    void documentWithMilestonesAndDependencies_resolves() {
        stubArtifactContent("""
                {
                  "milestones":[{"key":"m1","name":"Q3 Launch","description":"d","targetDate":"2026-09-01"}],
                  "epics":[
                    {"key":"e1","title":"Epic A","description":"d","motivation":"m","milestone":"m1",
                     "priority":"High","stories":[{"key":"s1","title":"S1","description":"s","priority":"Low",
                       "tasks":[{"key":"t1","title":"T1","description":"t","priority":"Medium"}]}]},
                    {"key":"e2","title":"Epic B","description":"d","motivation":"m","stories":[]}
                  ],
                  "dependencies":[{"blocking":"t1","blocked":"e2"}]
                }
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.milestones()).hasSize(1);
        assertThat(result.milestones().get(0).key()).isEqualTo("m1");
        assertThat(result.epics()).hasSize(2);
        assertThat(result.dependencies()).hasSize(1);
        assertThat(result.dependencies().get(0).blocking()).isEqualTo("t1");
        assertThat(result.dependencies().get(0).blocked()).isEqualTo("e2");
    }

    @Test
    void blankTopLevelTitle_failsValidation_resolvesToNull() {
        stubArtifactContent("""
                {"epics":[{"title":"","description":"desc","motivation":"why","stories":[]}]}
                """);

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void blankNestedStoryTitle_failsValidationViaCascade_resolvesToNull() {
        stubArtifactContent("""
                {"epics":[{"title":"Epic","description":"desc","motivation":"why",
                  "stories":[{"title":"","description":"s","tasks":[]}]}]}
                """);

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void blankNestedTaskTitle_failsValidationViaTwoLevelCascade_resolvesToNull() {
        stubArtifactContent("""
                {"epics":[{"title":"Epic","description":"desc","motivation":"why",
                  "stories":[{"title":"Story","description":"s","tasks":[{"title":"","description":"t"}]}]}]}
                """);

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void titleOver255Chars_failsValidation_resolvesToNull() {
        String tooLong = "x".repeat(256);
        stubArtifactContent("""
                {"epics":[{"title":"%s","description":"desc","motivation":"why","stories":[]}]}
                """.formatted(tooLong));

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void moreThanEightTopLevelEpics_failsValidation_resolvesToNull() {
        StringBuilder json = new StringBuilder("{\"epics\":[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"title\":\"Epic ")
                    .append(i)
                    .append("\",\"description\":\"d\",\"motivation\":\"m\",\"stories\":[]}");
        }
        json.append("]}");
        stubArtifactContent(json.toString());

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void exactlyEightTopLevelEpics_passesValidation_resolves() {
        StringBuilder json = new StringBuilder("{\"epics\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"title\":\"Epic ")
                    .append(i)
                    .append("\",\"description\":\"d\",\"motivation\":\"m\",\"stories\":[]}");
        }
        json.append("]}");
        stubArtifactContent(json.toString());

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);
        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(8);
    }

    @Test
    void malformedJson_stillDegradesToNullWithoutThrowing() {
        stubArtifactContent("{ not valid json [[[");

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void duplicateKeyAcrossItems_rejectsWholeDocument() {
        stubArtifactContent("""
                {"epics":[
                  {"key":"dup","title":"Epic A","description":"d","motivation":"m","stories":[]},
                  {"key":"dup","title":"Epic B","description":"d","motivation":"m","stories":[]}
                ]}
                """);

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void dependencyWithUnresolvedKey_isDroppedNotWholeDocument() {
        stubArtifactContent("""
                {"epics":[{"key":"e1","title":"Epic A","description":"d","motivation":"m","stories":[]}],
                 "dependencies":[{"blocking":"e1","blocked":"does-not-exist"}]}
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.dependencies()).isEmpty();
    }

    @Test
    void withinArtifactCycle_isDropped() {
        stubArtifactContent("""
                {"epics":[
                   {"key":"a","title":"Epic A","description":"d","motivation":"m","stories":[]},
                   {"key":"b","title":"Epic B","description":"d","motivation":"m","stories":[]}
                 ],
                 "dependencies":[{"blocking":"a","blocked":"b"},{"blocking":"b","blocked":"a"}]}
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(2);
        // The first edge (a -> b) is accepted; the second (b -> a) would close a cycle and is
        // dropped, so exactly one edge survives.
        assertThat(result.dependencies()).hasSize(1);
        assertThat(result.dependencies().get(0).blocking()).isEqualTo("a");
    }

    @Test
    void epicMilestoneReference_unresolved_isDroppedButEpicKept() {
        stubArtifactContent("""
                {"epics":[{"key":"e1","title":"Epic A","description":"d","motivation":"m",
                   "milestone":"no-such-milestone","stories":[]}]}
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.epics().get(0).milestone()).isNull();
    }

    @Test
    void blankPriority_toleratedAtEveryLevel() {
        stubArtifactContent("""
                {"epics":[{"title":"Epic A","description":"d","motivation":"m","priority":"",
                   "stories":[{"title":"S1","description":"s","priority":"","tasks":[
                     {"title":"T1","description":"t","priority":""}]}]}]}
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.epics().get(0).priority()).isEmpty();
    }

    @Test
    void selfReferentialEdge_isDropped() {
        stubArtifactContent("""
                {"epics":[{"key":"a","title":"Epic A","description":"d","motivation":"m","stories":[]}],
                 "dependencies":[{"blocking":"a","blocked":"a"}]}
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        // Both keys resolve, but a self-referential edge (blocking == blocked) is dropped by the
        // dedicated self-reference guard rather than materialized into an item that blocks itself.
        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.dependencies()).isEmpty();
    }

    @Test
    void documentWithNoAnchors_neverResolvesProjectOrLooksUpAnchors() {
        stubArtifactContent("""
                {"epics":[{"title":"Epic A","description":"d","motivation":"m","stories":[]}]}
                """);

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        Mockito.verifyNoInteractions(internalRunService, anchorLookup);
    }

    @Test
    void anchorInProject_fillsLiveTitleAndDescription() {
        UUID anchorId = UUID.randomUUID();
        stubArtifactContent("""
                {"epics":[{"existingId":"%s","stories":[]}]}
                """.formatted(anchorId));
        Mockito.when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(projectId);
        Epic liveEpic = new Epic();
        liveEpic.setId(anchorId);
        liveEpic.setTitle("Live Title");
        liveEpic.setDescription("Live Description");
        Mockito.when(anchorLookup.epic(anchorId, projectId)).thenReturn(Optional.of(liveEpic));

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.epics().get(0).title()).isEqualTo("Live Title");
        assertThat(result.epics().get(0).description()).isEqualTo("Live Description");
        assertThat(result.epics().get(0).existingId()).isEqualTo(anchorId);
    }

    @Test
    void foreignOrMissingAnchor_isBlanked() {
        UUID anchorId = UUID.randomUUID();
        stubArtifactContent("""
                {"epics":[{"existingId":"%s","stories":[]}]}
                """.formatted(anchorId));
        Mockito.when(internalRunService.resolveSoftwareProjectId(runId)).thenReturn(projectId);
        Mockito.when(anchorLookup.epic(anchorId, projectId)).thenReturn(Optional.empty());

        RoadmapCandidatesDocument result = resolver.resolve(runId, templateNodeId);

        assertThat(result).isNotNull();
        assertThat(result.epics()).hasSize(1);
        assertThat(result.epics().get(0).title()).isEmpty();
        assertThat(result.epics().get(0).description()).isEmpty();
    }

    private final UUID implementExecId = UUID.randomUUID();
    private final UUID codeReviewExecId = UUID.randomUUID();

    private static String doc(String epicTitle) {
        return """
                {"epics":[{"title":"%s","description":"d","motivation":"m",
                  "stories":[{"title":"S","description":"s","tasks":[{"title":"T","description":"t"}]}]}]}
                """.formatted(epicTitle);
    }

    /** Final Approval's declaration order: Implement first, then Code Review. */
    private List<ResolvedArtifactGroup> implementThenCodeReview() {
        return List.of(
                new ResolvedArtifactGroup(
                        implementExecId,
                        "implement",
                        List.of(new ResolvedArtifactEntry("roadmap_candidates.json", "proposal", false))),
                new ResolvedArtifactGroup(
                        codeReviewExecId,
                        "code_review",
                        List.of(
                                new ResolvedArtifactEntry("review.md", "review", true),
                                new ResolvedArtifactEntry("roadmap_candidates.json", "proposal", false))));
    }

    private void completed(Instant implementAt, Instant codeReviewAt) {
        NodeExecution impl = Mockito.mock(NodeExecution.class);
        Mockito.when(impl.getId()).thenReturn(implementExecId);
        Mockito.when(impl.getCompletedAt()).thenReturn(implementAt);
        NodeExecution review = Mockito.mock(NodeExecution.class);
        Mockito.when(review.getId()).thenReturn(codeReviewExecId);
        Mockito.when(review.getCompletedAt()).thenReturn(codeReviewAt);
        Mockito.when(nodeExecutionRepository.findAllById(Mockito.any())).thenReturn(List.of(impl, review));
    }

    private void content(UUID execId, String json) {
        Mockito.when(artifactService.getArtifactContent(runId, execId, "roadmap_candidates.json"))
                .thenReturn(json);
    }

    private void missing(UUID execId) {
        Mockito.when(artifactService.getArtifactContent(runId, execId, "roadmap_candidates.json"))
                .thenThrow(new NotFoundException("Artifact not found: roadmap_candidates.json"));
    }

    @Test
    void newestProducerWins_codeReviewAfterImplement() {
        completed(Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T11:00:00Z"));
        content(implementExecId, doc("From Implement"));
        content(codeReviewExecId, doc("From Code Review"));

        RoadmapCandidatesDocument result = resolver.resolve(runId, implementThenCodeReview());

        assertThat(result).isNotNull();
        assertThat(result.epics().get(0).title()).isEqualTo("From Code Review");
    }

    @Test
    void newestProducerWithoutFile_fallsBackToOlderProducer() {
        completed(Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T11:00:00Z"));
        content(implementExecId, doc("From Implement"));
        missing(codeReviewExecId);

        RoadmapCandidatesDocument result = resolver.resolve(runId, implementThenCodeReview());

        assertThat(result).isNotNull();
        assertThat(result.epics().get(0).title()).isEqualTo("From Implement");
    }

    @Test
    void implementNewerThanCodeReview_implementWins() {
        // A Supervisor route can re-run Implement without a Code Review after it.
        completed(Instant.parse("2026-10-08T12:00:00Z"), Instant.parse("2026-10-08T11:00:00Z"));
        content(implementExecId, doc("From Implement"));
        content(codeReviewExecId, doc("From Code Review"));

        RoadmapCandidatesDocument result = resolver.resolve(runId, implementThenCodeReview());

        assertThat(result.epics().get(0).title()).isEqualTo("From Implement");
    }

    @Test
    void noProducerHasFile_resolvesToNull() {
        completed(Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T11:00:00Z"));
        missing(implementExecId);
        missing(codeReviewExecId);

        assertThat(resolver.resolve(runId, implementThenCodeReview())).isNull();
    }

    @Test
    void newestCopyMalformed_resolvesToNullWithoutFallback() {
        completed(Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T11:00:00Z"));
        content(implementExecId, doc("From Implement"));
        content(codeReviewExecId, "{not json");

        assertThat(resolver.resolve(runId, implementThenCodeReview())).isNull();
    }

    @Test
    void unknownCompletionTime_sortsLast() {
        completed(Instant.parse("2026-10-08T10:00:00Z"), null);
        content(implementExecId, doc("From Implement"));
        content(codeReviewExecId, doc("From Code Review"));

        assertThat(resolver.resolve(runId, implementThenCodeReview())
                        .epics()
                        .get(0)
                        .title())
                .isEqualTo("From Implement");
    }

    @Test
    void newestCopyEmpty_winsOverOlderNonEmptyCopy() {
        // Code Review removing every entry installs an empty document; it must not revive Implement's.
        completed(Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T11:00:00Z"));
        content(implementExecId, doc("From Implement"));
        content(codeReviewExecId, "{\"epics\":[]}");

        RoadmapCandidatesDocument result = resolver.resolve(runId, implementThenCodeReview());

        assertThat(result).isNotNull();
        assertThat(result.epics()).isEmpty();
    }
}
