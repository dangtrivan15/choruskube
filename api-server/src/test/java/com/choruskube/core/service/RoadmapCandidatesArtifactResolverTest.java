package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers the Bean Validation guardrail added to {@link RoadmapCandidatesArtifactResolver}: an
 * AI-authored {@code roadmap_candidates.json} artifact must satisfy the identical {@code @NotBlank}/
 * {@code @Size} constraint tree that {@code SignalRequest.editedCandidates} enforces on the
 * reviewer-edited path, not just be well-formed JSON. Without this, a blank title or an oversized
 * breakdown could bypass every constraint a human-submitted edit is held to and materialize
 * straight into Epic/Story/Task rows.
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
                new ObjectMapper(),
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    private List<ResolvedArtifactGroup> requiredArtifacts() {
        return List.of(new ResolvedArtifactGroup(
                analyzerExecId,
                "roadmap_analyzer",
                List.of(new ResolvedArtifactEntry("roadmap_candidates.json", "Structured breakdown"))));
    }

    private void stubArtifactContent(String json) {
        Mockito.when(artifactResolutionService.resolveRequiredArtifacts(templateNodeId, runId))
                .thenReturn(requiredArtifacts());
        Mockito.when(artifactService.getArtifactContent(runId, analyzerExecId, "roadmap_candidates.json"))
                .thenReturn(json);
    }

    @Test
    void wellFormedAndValidArtifact_resolvesCandidates() {
        stubArtifactContent("""
                [
                  {"title":"Bulk Import","description":"desc","motivation":"why",
                   "stories":[{"title":"Story 1","description":"s","tasks":[{"title":"Task 1","description":"t"}]}]}
                ]
                """);

        List<CandidateEpicProposal> result = resolver.resolve(runId, templateNodeId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Bulk Import");
    }

    @Test
    void blankTopLevelTitle_failsValidation_resolvesToNull() {
        stubArtifactContent("""
                [{"title":"","description":"desc","motivation":"why","stories":[]}]
                """);

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void blankNestedStoryTitle_failsValidationViaCascade_resolvesToNull() {
        stubArtifactContent("""
                [{"title":"Epic","description":"desc","motivation":"why",
                  "stories":[{"title":"","description":"s","tasks":[]}]}]
                """);

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void blankNestedTaskTitle_failsValidationViaTwoLevelCascade_resolvesToNull() {
        stubArtifactContent("""
                [{"title":"Epic","description":"desc","motivation":"why",
                  "stories":[{"title":"Story","description":"s","tasks":[{"title":"","description":"t"}]}]}]
                """);

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void titleOver255Chars_failsValidation_resolvesToNull() {
        String tooLong = "x".repeat(256);
        stubArtifactContent("""
                [{"title":"%s","description":"desc","motivation":"why","stories":[]}]
                """.formatted(tooLong));

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void moreThanEightTopLevelEpics_failsValidation_resolvesToNull() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"title\":\"Epic ")
                    .append(i)
                    .append("\",\"description\":\"d\",\"motivation\":\"m\",\"stories\":[]}");
        }
        json.append("]");
        stubArtifactContent(json.toString());

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }

    @Test
    void exactlyEightTopLevelEpics_passesValidation_resolves() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"title\":\"Epic ")
                    .append(i)
                    .append("\",\"description\":\"d\",\"motivation\":\"m\",\"stories\":[]}");
        }
        json.append("]");
        stubArtifactContent(json.toString());

        assertThat(resolver.resolve(runId, templateNodeId)).hasSize(8);
    }

    @Test
    void malformedJson_stillDegradesToNullWithoutThrowing() {
        stubArtifactContent("{ not valid json [[[");

        assertThat(resolver.resolve(runId, templateNodeId)).isNull();
    }
}
