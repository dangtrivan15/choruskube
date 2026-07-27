package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.PendingGateResponse;
import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.jpa.domain.Specification;

/**
 * Covers Decision 1's degrade-gracefully contract for {@code PendingGateResponse.candidateBreakdown}:
 * a well-formed {@code roadmap_candidates.json} populates it; a malformed one, or one that simply
 * isn't among the node's resolved required artifacts, degrades to {@code null} without ever
 * throwing — the rest of the gate response must still succeed either way.
 */
class PendingGateServiceCandidateBreakdownTest {

    private NodeExecutionRepository execRepo;
    private WorkflowRunRepository runRepo;
    private GraphSnapshotBuilder snapshotBuilder;
    private ArtifactResolutionService artifactResolutionService;
    private ArtifactService artifactService;
    private ObjectMapper objectMapper;
    private PendingGateService service;

    private final UUID runId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private final UUID analyzerExecId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        execRepo = Mockito.mock(NodeExecutionRepository.class);
        runRepo = Mockito.mock(WorkflowRunRepository.class);
        snapshotBuilder = Mockito.mock(GraphSnapshotBuilder.class);
        artifactResolutionService = Mockito.mock(ArtifactResolutionService.class);
        artifactService = Mockito.mock(ArtifactService.class);
        objectMapper = new ObjectMapper();

        RoadmapCandidatesArtifactResolver candidatesArtifactResolver = new RoadmapCandidatesArtifactResolver(
                artifactResolutionService,
                artifactService,
                objectMapper,
                Validation.buildDefaultValidatorFactory().getValidator());

        service = new PendingGateService(
                execRepo,
                runRepo,
                snapshotBuilder,
                objectMapper,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                artifactResolutionService,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                candidatesArtifactResolver);
    }

    private void stubGate(String snapshot, List<ResolvedArtifactGroup> requiredArtifacts) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setName("Roadmap run");
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(exec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(exec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
        Mockito.when(artifactResolutionService.resolveRequiredArtifacts(templateNodeId, runId))
                .thenReturn(requiredArtifacts);
    }

    private String gateSnapshot() {
        return """
                {"nodes": [{"template_node_id": "%s", "label": "roadmap_human_gate"}], "edges": []}
                """.formatted(templateNodeId);
    }

    @Test
    void wellFormedArtifact_populatesCandidateBreakdown() {
        List<ResolvedArtifactGroup> requiredArtifacts = List.of(new ResolvedArtifactGroup(
                analyzerExecId,
                "roadmap_analyzer",
                List.of(
                        new ResolvedArtifactEntry("roadmap_analysis.md", "Analysis"),
                        new ResolvedArtifactEntry("roadmap_candidates.json", "Structured breakdown"))));
        stubGate(gateSnapshot(), requiredArtifacts);

        String json = """
                [
                  {"title":"Bulk Import","description":"desc","motivation":"why","repos":["repo-a"],"priority":"High",
                   "stories":[{"title":"Story 1","description":"s-desc","tasks":[{"title":"Task 1","description":"t-desc"}]}]}
                ]
                """;
        Mockito.when(artifactService.getArtifactContent(runId, analyzerExecId, "roadmap_candidates.json"))
                .thenReturn(json);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        List<CandidateEpicProposal> breakdown = result.get(0).candidateBreakdown();
        assertThat(breakdown).isNotNull();
        assertThat(breakdown).hasSize(1);
        assertThat(breakdown.get(0).title()).isEqualTo("Bulk Import");
        assertThat(breakdown.get(0).stories()).hasSize(1);
        assertThat(breakdown.get(0).stories().get(0).tasks()).hasSize(1);
    }

    @Test
    void malformedJson_degradesToNullWithoutThrowing() {
        List<ResolvedArtifactGroup> requiredArtifacts = List.of(new ResolvedArtifactGroup(
                analyzerExecId,
                "roadmap_analyzer",
                List.of(new ResolvedArtifactEntry("roadmap_candidates.json", "Structured breakdown"))));
        stubGate(gateSnapshot(), requiredArtifacts);

        Mockito.when(artifactService.getArtifactContent(runId, analyzerExecId, "roadmap_candidates.json"))
                .thenReturn("{ not valid json [[[");

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).candidateBreakdown()).isNull();
        // The rest of the gate response still succeeds despite the malformed artifact.
        assertThat(result.get(0).nodeLabel()).isEqualTo("roadmap_human_gate");
    }

    @Test
    void artifactFailsBeanValidation_degradesToNullWithoutThrowing() {
        // A well-formed-JSON but invalid artifact (blank title) must be rejected the same way a
        // reviewer-submitted edit would be by SignalRequest's own @Valid cascade — not silently
        // accepted just because it skipped the controller-bound validation path.
        List<ResolvedArtifactGroup> requiredArtifacts = List.of(new ResolvedArtifactGroup(
                analyzerExecId,
                "roadmap_analyzer",
                List.of(new ResolvedArtifactEntry("roadmap_candidates.json", "Structured breakdown"))));
        stubGate(gateSnapshot(), requiredArtifacts);

        String json = """
                [
                  {"title":"","description":"desc","motivation":"why","stories":[]}
                ]
                """;
        Mockito.when(artifactService.getArtifactContent(runId, analyzerExecId, "roadmap_candidates.json"))
                .thenReturn(json);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).candidateBreakdown()).isNull();
        // The rest of the gate response still succeeds despite the invalid artifact.
        assertThat(result.get(0).nodeLabel()).isEqualTo("roadmap_human_gate");
    }

    @Test
    void artifactAbsent_nonRoadmapTemplate_candidateBreakdownIsNull() {
        // A gate from a template that doesn't produce a structured breakdown at all — e.g.
        // it only requires a plain markdown artifact.
        List<ResolvedArtifactGroup> requiredArtifacts = List.of(new ResolvedArtifactGroup(
                analyzerExecId, "some_other_node", List.of(new ResolvedArtifactEntry("summary.md", "Summary"))));
        stubGate(gateSnapshot(), requiredArtifacts);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).candidateBreakdown()).isNull();
        Mockito.verifyNoInteractions(artifactService);
    }

    @Test
    void nullRequiredArtifacts_candidateBreakdownIsNull() {
        stubGate(gateSnapshot(), null);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).candidateBreakdown()).isNull();
    }
}
