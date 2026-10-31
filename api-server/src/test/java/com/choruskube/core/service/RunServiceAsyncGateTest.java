package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.dto.SignalResponse;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.DriftRecheckConfig;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Which approve clicks are handed to {@link GateFinalizeService}: only an approval the gate has
 * finalizing work for, and only when the gate is marked {@code finalize_mode = 'async'} — an
 * unmarked gate with finalizing work is refused outright. Every other decision, and an approval
 * with nothing to finalize, claims the gate and sends a final signal instead.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunServiceAsyncGateTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private WorkflowStub workflowStub;

    @Mock
    private WorkflowClientRegistry workflowClients;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private RoadmapCandidatesArtifactResolver roadmapCandidatesArtifactResolver;

    @Mock
    private NodeExecutionClaimService nodeExecutionClaimService;

    @Mock
    private GateRecheckService gateRecheck;

    @Mock
    private AuditSink auditSink;

    @Mock
    private GateFinalizeService gateFinalize;

    @Mock
    private RoadmapProposalValidator roadmapProposalValidator;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private WorkflowRun run;
    private NodeExecution exec;

    private static final String RECHECK_AND_MATERIALIZE_GATE = "{\"terminal_decisions\":[\"approved\"],"
            + "\"materialize\":\"roadmap_extension\",\"merge_pull_requests\":\"squash\","
            + "\"drift_recheck\":{\"route\":\"rereview\",\"stamp\":\"if_unchanged\"}}";
    private static final String PLAIN_GATE = "{\"terminal_decisions\":[\"approved\"]}";
    private static final String MERGE_ONLY_GATE =
            "{\"terminal_decisions\":[\"approved\"],\"merge_pull_requests\":\"squash\"}";
    private static final String RECHECK_ONLY_GATE = "{\"terminal_decisions\":[\"approved\"],"
            + "\"drift_recheck\":{\"route\":\"rereview\",\"stamp\":\"if_unchanged\"}}";
    private static final String MATERIALIZE_ONLY_GATE =
            "{\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_extension\"}";

    @BeforeEach
    void setUp() {
        service = new RunService(
                runRepo,
                execRepo,
                null,
                snapshotBuilder,
                null,
                null,
                null,
                null,
                objectMapper,
                eventPublisher,
                null,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(),
                null,
                workflowClients,
                null,
                auditSink,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null,
                roadmapCandidatesArtifactResolver,
                nodeExecutionClaimService,
                null,
                roadmapProposalValidator,
                null,
                gateRecheck,
                gateFinalize);
        lenient().when(workflowClients.clientFor(any())).thenReturn(workflowClient);
        lenient().when(workflowClient.newUntypedWorkflowStub("wf-1")).thenReturn(workflowStub);

        exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        exec.setFinalizeMode("async");
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        lenient()
                .when(nodeExecutionClaimService.compareAndSetStatus(any(), any(), any()))
                .thenReturn(1);
        when(gateFinalize.requestApproval(any(), any(), any(), any(), any(), any()))
                .thenReturn(SignalResponse.finalizing());
    }

    private void stubRun(String nodeConfig) {
        run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("wf-1");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run))
                .thenReturn("""
                {"nodes":[{"template_node_id":"%s","label":"final_approval","executor_type":"human","config_overrides":%s}],
                 "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"rereview"}]}""".formatted(templateNodeId, nodeConfig, templateNodeId, UUID.randomUUID()));
    }

    private String signalled() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), payload.capture());
        return payload.getValue().toString();
    }

    @Test
    void asyncApproval_ofAGateWithFinalizingWork_isHandedOver_withTheReviewersDocument() {
        stubRun(RECHECK_AND_MATERIALIZE_GATE);
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(null, List.of(), null);

        SignalResponse response = service.signalHumanDecision(
                runId, nodeExecId, new SignalRequest("Approved", "ship it", "{\"a\":\"k\"}", edited));

        assertThat(response.outcome()).isEqualTo(SignalResponse.FINALIZING);
        verify(gateFinalize)
                .requestApproval(
                        eq(run),
                        eq(exec),
                        any(SignalRequest.class),
                        any(),
                        eq(new DriftRecheckConfig("rereview", StampMode.if_unchanged)),
                        eq(edited));
        verifyNoInteractions(nodeExecutionClaimService, workflowStub);
    }

    @Test
    void asyncApproval_withoutEdits_recordsTheAnalyzersDocument() {
        stubRun(RECHECK_AND_MATERIALIZE_GATE);
        RoadmapCandidatesDocument analyzed = new RoadmapCandidatesDocument(null, List.of(), null);
        when(roadmapCandidatesArtifactResolver.resolve(runId, templateNodeId)).thenReturn(analyzed);

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        verify(gateFinalize).requestApproval(any(), any(), any(), any(), any(), eq(analyzed));
    }

    @Test
    void asyncApproval_withAnInvalidRoadmapEdit_isRejectedBeforeAnyClaim() {
        stubRun(RECHECK_AND_MATERIALIZE_GATE);
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(null, List.of(), null);
        when(roadmapProposalValidator.validate(
                        runId,
                        edited,
                        RoadmapMaterializeMode.roadmap_extension,
                        RoadmapProposalValidator.Strictness.GATE))
                .thenReturn(List.of("a new story needs at least one task"));

        assertThatThrownBy(() -> service.signalHumanDecision(
                        runId, nodeExecId, new SignalRequest("approved", "ship it", null, edited)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.getErrors())
                        .containsExactly("a new story needs at least one task"));

        verifyNoInteractions(gateFinalize, nodeExecutionClaimService, workflowStub);
    }

    @Test
    void rereview_signalsAFinalDecisionWithoutAnApprovalId() {
        stubRun(RECHECK_AND_MATERIALIZE_GATE);

        SignalResponse response =
                service.signalHumanDecision(runId, nodeExecId, new SignalRequest("rereview", "redo it", null, null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.SIGNALED);
        verify(gateFinalize, never()).requestApproval(any(), any(), any(), any(), any(), any());
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running);
        verify(gateRecheck).voidPendingFor(runId, templateNodeId, GateRecheckService.REVIEWER_DECIDED);
        assertThat(signalled()).contains("rereview").contains("approvalId=null");
    }

    @Test
    void approval_ofAPlainGate_signalsWithoutHandingOver() {
        stubRun(PLAIN_GATE);

        SignalResponse response =
                service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.SIGNALED);
        verify(gateFinalize, never()).requestApproval(any(), any(), any(), any(), any(), any());
        assertThat(signalled()).contains("approved");
    }

    @Test
    void asyncGate_invalidDecision_isRejectedBeforeAnyClaim() {
        stubRun(RECHECK_AND_MATERIALIZE_GATE);

        assertThatThrownBy(() ->
                        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("ship-it", null, null, null)))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(gateFinalize, nodeExecutionClaimService);
    }

    @ParameterizedTest
    @ValueSource(strings = {MERGE_ONLY_GATE, RECHECK_ONLY_GATE, MATERIALIZE_ONLY_GATE, RECHECK_AND_MATERIALIZE_GATE})
    void approve_unmarkedGateWithFinalizeWork_isRefused_andClaimsNothing(String nodeConfig) {
        exec.setFinalizeMode(null);
        stubRun(nodeConfig);

        assertThatThrownBy(() ->
                        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null)))
                .isExactlyInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot finalize an approval");

        verifyNoInteractions(nodeExecutionClaimService, gateFinalize, gateRecheck, workflowStub);
    }

    @Test
    void approve_unmarkedPlainGate_signals() {
        exec.setFinalizeMode(null);
        stubRun(PLAIN_GATE);

        SignalResponse response =
                service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.SIGNALED);
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running);
        verifyNoInteractions(gateFinalize);
        assertThat(signalled()).contains("approved");
    }

    @Test
    void signalFailure_releasesTheClaim_andRethrows() {
        stubRun(PLAIN_GATE);
        RuntimeException unreachable = new RuntimeException("temporal unreachable");
        doThrow(unreachable).when(workflowStub).signal(eq("human-decision-" + nodeExecId), any());

        assertThatThrownBy(() ->
                        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null)))
                .isSameAs(unreachable);

        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rereview_unmarkedGate_signals() {
        exec.setFinalizeMode(null);
        stubRun(RECHECK_AND_MATERIALIZE_GATE);

        SignalResponse response =
                service.signalHumanDecision(runId, nodeExecId, new SignalRequest("rereview", "redo it", null, null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.SIGNALED);
        verify(gateFinalize, never()).requestApproval(any(), any(), any(), any(), any(), any());
        assertThat(signalled()).contains("rereview").contains("approvalId=null");
    }

    @Test
    void signalGate_withAnApprovalId_carriesIt() throws Exception {
        stubRun(PLAIN_GATE);
        UUID approvalId = UUID.randomUUID();

        service.signalGate(run, nodeExecId, "approved", "ship it", null, approvalId);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), payload.capture());
        assertThat(objectMapper.writeValueAsString(payload.getValue()))
                .contains("\"approvalId\":\"" + approvalId + "\"")
                .contains("\"attachmentRefs\":\"{}\"");
    }

    @Test
    void signalGate_withoutAnApprovalId_omitsTheField() throws Exception {
        stubRun(PLAIN_GATE);

        service.signalGate(run, nodeExecId, "rereview", null, null, null);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), payload.capture());
        assertThat(objectMapper.writeValueAsString(payload.getValue())).doesNotContain("approvalId");
        verify(gateRecheck, never()).begin(any(), any(), any(), any(), any(), any(), isNull());
    }
}
