package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the deterministic-materialization contract on {@code RunService.signalHumanDecision}:
 * on an "approved" decision for a gate configured with {@code materialize: "roadmap_candidates"},
 * the reviewed breakdown is materialized via {@link RoadmapCandidateMaterializer}; otherwise
 * (rejected, or a gate without the marker) nothing is materialized at all.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceRoadmapMaterializationTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private TemplateEdgeRepository edgeRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private TemplateNodeRepository templateNodeRepo;

    @Mock
    private GraphValidationService validationService;

    @Mock
    private ExecutionLogRepository executionLogRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private WorkflowStub workflowStub;

    @Mock
    private RoadmapCandidateMaterializer roadmapCandidateMaterializer;

    @Mock
    private RoadmapCandidatesArtifactResolver roadmapCandidatesArtifactResolver;

    @Mock
    private NodeExecutionClaimService nodeExecutionClaimService;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                workflowClient,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                objectMapper,
                eventPublisher,
                gitRepoRepo,
                null,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(),
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null, // storyRepo
                null, // epicRepo
                null,
                null,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                roadmapCandidateMaterializer,
                roadmapCandidatesArtifactResolver,
                nodeExecutionClaimService,
                null); // escalationContextResolver - unused (escalation not exercised)
    }

    private NodeExecution stubExec() {
        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        lenient()
                .when(nodeExecutionClaimService.compareAndSetStatus(any(), any(), any()))
                .thenReturn(1);
        return exec;
    }

    private WorkflowRun stubRun(String nodeConfigOverridesJson) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("test-workflow-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        String configOverridesField =
                nodeConfigOverridesJson == null ? "" : ",\"config_overrides\":" + nodeConfigOverridesJson;
        String snapshot = """
                {"nodes":[{"template_node_id":"%s","label":"roadmap_human_gate","executor_type":"human","timeout_seconds":86400%s}],
                 "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"rejected"}]}""".formatted(templateNodeId, configOverridesField, templateNodeId, UUID.randomUUID());
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
        lenient()
                .when(workflowClient.newUntypedWorkflowStub("test-workflow-id"))
                .thenReturn(workflowStub);
        return run;
    }

    private static final String MATERIALIZE_GATE_CONFIG =
            "{\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_candidates\"}";

    private static RoadmapCandidatesDocument sampleCandidates() {
        return new RoadmapCandidatesDocument(
                null,
                List.of(new CandidateEpicProposal(
                        "Bulk Import",
                        "desc",
                        "why",
                        List.of("repo-a"),
                        "High",
                        List.of(new CandidateStoryProposal(
                                "Story 1",
                                "s-desc",
                                List.of(new CandidateTaskProposal("Task 1", "t-desc", null, null)),
                                null,
                                null)),
                        null,
                        null)),
                null);
    }

    @Test
    void approvedDecisionOnMaterializeNode_materializesEditedCandidates() {
        stubExec();
        stubRun(MATERIALIZE_GATE_CONFIG);
        RoadmapCandidatesDocument edited = sampleCandidates();
        when(roadmapCandidateMaterializer.materialize(eq(runId), eq(edited)))
                .thenReturn(new MaterializationSummary(List.of(UUID.randomUUID()), List.of(), 0, List.of()));

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, edited));

        verify(roadmapCandidateMaterializer).materialize(runId, edited);
        verifyNoInteractions(roadmapCandidatesArtifactResolver);
    }

    @Test
    void approvedDecisionWithNoEditedCandidates_materializesAnalyzerArtifact() {
        stubExec();
        stubRun(MATERIALIZE_GATE_CONFIG);
        RoadmapCandidatesDocument fromArtifact = sampleCandidates();
        when(roadmapCandidatesArtifactResolver.resolve(runId, templateNodeId)).thenReturn(fromArtifact);
        when(roadmapCandidateMaterializer.materialize(runId, fromArtifact))
                .thenReturn(new MaterializationSummary(List.of(UUID.randomUUID()), List.of(), 0, List.of()));

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        verify(roadmapCandidatesArtifactResolver).resolve(runId, templateNodeId);
        verify(roadmapCandidateMaterializer).materialize(runId, fromArtifact);
    }

    @Test
    void oneMalformedCandidateAmongSeveral_restStillMaterialize() {
        stubExec();
        stubRun(MATERIALIZE_GATE_CONFIG);
        CandidateEpicProposal good = sampleCandidates().epics().get(0);
        CandidateEpicProposal malformed = new CandidateEpicProposal(null, null, null, null, null, null, null, null);
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(null, List.of(good, malformed), null);

        // The materializer itself owns per-candidate best-effort behavior (see
        // DefaultRoadmapCandidateMaterializer) — RunService just needs to pass the full
        // document through and surface whatever summary comes back, unconditionally.
        when(roadmapCandidateMaterializer.materialize(runId, edited))
                .thenReturn(new MaterializationSummary(
                        List.of(UUID.randomUUID()),
                        List.of(),
                        0,
                        List.of("Failed to materialize candidate Epic 'null': boom")));

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, edited));

        verify(roadmapCandidateMaterializer).materialize(runId, edited);
    }

    @Test
    void approvedDecisionWithCandidatesEditedToEmpty_materializesEmptyDocumentInsteadOfFallingBackToArtifact() {
        stubExec();
        stubRun(MATERIALIZE_GATE_CONFIG);
        // The reviewer explicitly cleared every candidate (an empty, non-null document) rather than
        // submitting no edits at all — this must NOT fall back to the original analyzer artifact.
        RoadmapCandidatesDocument empty = new RoadmapCandidatesDocument(List.of(), List.of(), List.of());
        when(roadmapCandidateMaterializer.materialize(runId, empty))
                .thenReturn(new MaterializationSummary(List.of(), List.of(), 0, List.of()));

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, empty));

        verify(roadmapCandidateMaterializer).materialize(runId, empty);
        verifyNoInteractions(roadmapCandidatesArtifactResolver);
    }

    @Test
    void approvedDecisionWithNoEditsAndUnresolvableArtifact_skipsMaterializationWithoutError() {
        stubExec();
        stubRun(MATERIALIZE_GATE_CONFIG);
        // RoadmapCandidatesArtifactResolver degrades to null (never throws) when the candidate
        // breakdown artifact is missing or malformed — the signal must still succeed, and the
        // gap must be visible in the result rather than silently doing nothing.
        when(roadmapCandidatesArtifactResolver.resolve(runId, templateNodeId)).thenReturn(null);

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        verify(roadmapCandidatesArtifactResolver).resolve(runId, templateNodeId);
        verifyNoInteractions(roadmapCandidateMaterializer);
        // HumanDecisionPayload is a private record internal to RunService; assert against its
        // auto-generated toString() rather than reaching into the type directly.
        org.mockito.ArgumentCaptor<Object> payloadCaptor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue().toString())
                .contains("Materialization skipped: no candidate breakdown was found for this run");
    }

    @Test
    void rejectedDecision_neverMaterializes() {
        stubExec();
        stubRun(MATERIALIZE_GATE_CONFIG);

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("rejected", null, null, sampleCandidates()));

        verifyNoInteractions(roadmapCandidateMaterializer);
        verifyNoInteractions(roadmapCandidatesArtifactResolver);
    }

    @Test
    void approvedDecisionOnNodeWithoutMaterializeMarker_neverMaterializes() {
        stubExec();
        // terminal_decisions present (so "approved" validates) but no "materialize" key —
        // cross-template leakage regression: a shared decision string must not trigger
        // materialization on a node that isn't configured for it.
        stubRun("{\"terminal_decisions\":[\"approved\"]}");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, sampleCandidates()));

        verifyNoInteractions(roadmapCandidateMaterializer);
        verifyNoInteractions(roadmapCandidatesArtifactResolver);
    }
}
