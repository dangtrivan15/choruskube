package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers {@link RoadmapCandidatesArtifactResolver}: the Bean Validation guardrail that an
 * AI-authored {@code roadmap_candidates.json} artifact must satisfy the identical {@code
 * @NotBlank}/{@code @Size} constraint tree that {@code SignalRequest.editedCandidates} enforces on
 * the reviewer-edited path (not just be well-formed JSON), plus the document-shape parsing
 * (Decision 5), legacy bare-array back-compat, and the key/reference/cycle validation added on top
 * of Bean Validation (Decision 2/3).
 */
class RoadmapCandidatesArtifactResolverTest {

    private ArtifactResolutionService artifactResolutionService;
    private ArtifactService artifactService;
    private RoadmapCandidatesArtifactResolver resolver;

    private final UUID runId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private final UUID analyzerExecId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        artifactResolutionService = Mockito.mock(ArtifactResolutionService.class);
        artifactService = Mockito.mock(ArtifactService.class);
        resolver = new RoadmapCandidatesArtifactResolver(
                artifactResolutionService,
                artifactService,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Validation.buildDefaultValidatorFactory().getValidator());
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
}
