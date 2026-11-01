package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.dto.SignalResponse;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.GateClaimConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** A gate decision the system makes on a run's behalf: the human path's claim and validation, no caller. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunServiceSystemGateDecisionTest {

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
    private NodeExecutionClaimService nodeExecutionClaimService;

    @Mock
    private GateRecheckService gateRecheck;

    @Mock
    private AuditSink auditSink;

    @Mock
    private AuthorizationService authService;

    private RunService service;
    private final UUID runId = UUID.randomUUID();
    private final UUID gateId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private NodeExecution gate;

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
                new ObjectMapper(),
                eventPublisher,
                null,
                authService,
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
                null,
                nodeExecutionClaimService,
                null,
                null,
                null,
                null,
                null,
                gateRecheck,
                null,
                null // storySpecService
                );
        lenient().when(workflowClients.clientFor(any())).thenReturn(workflowClient);
        lenient().when(workflowClient.newUntypedWorkflowStub("wf-1")).thenReturn(workflowStub);
        lenient()
                .when(nodeExecutionClaimService.compareAndSetStatus(any(), any(), any()))
                .thenReturn(1);
        // Any caller check would fail: the system path must not make one.
        lenient().doThrow(new ForbiddenException("no caller")).when(authService).checkOrgAccess(anyString(), any());

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run.setExternalRunId("wf-1");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run))
                .thenReturn("""
                {"nodes":[{"template_node_id":"%s","label":"await_story_revision","executor_type":"human","config_overrides":{}}],
                 "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"resume"}]}""".formatted(templateNodeId, templateNodeId, UUID.randomUUID()));

        gate = new NodeExecution();
        gate.setId(gateId);
        gate.setWorkflowRunId(runId);
        gate.setTemplateNodeId(templateNodeId);
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        when(execRepo.findById(gateId)).thenReturn(Optional.of(gate));
    }

    @Test
    void decideGateAsSystem_claimsAndSignalsWithoutAuthorizingACaller() {
        SignalResponse response = service.decideGateAsSystem(
                runId, gateId, new SignalRequest("resume", null, "{\"human_guidance.md\":\"k\"}", null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.SIGNALED);
        verify(authService, never()).checkOrgAccess(anyString(), any());
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(gateId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + gateId), payload.capture());
        assertThat(payload.getValue().toString()).contains("resume").contains("human_guidance.md");
    }

    @Test
    void signalHumanDecision_stillAuthorizesTheCaller() {
        assertThatThrownBy(
                        () -> service.signalHumanDecision(runId, gateId, new SignalRequest("resume", null, null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void decideGateAsSystem_gateAlreadyClaimed_conflicts() {
        when(nodeExecutionClaimService.compareAndSetStatus(
                        gateId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running))
                .thenReturn(0);

        assertThatThrownBy(
                        () -> service.decideGateAsSystem(runId, gateId, new SignalRequest("resume", null, null, null)))
                .isInstanceOf(GateClaimConflictException.class);
        verify(workflowStub, never()).signal(anyString(), any());
    }

    @Test
    void decideGateAsSystem_invalidDecision_releasesTheClaim() {
        assertThatThrownBy(() ->
                        service.decideGateAsSystem(runId, gateId, new SignalRequest("approved", null, null, null)))
                .isInstanceOf(RuntimeException.class);
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(gateId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
        verify(workflowStub, never()).signal(anyString(), any());
    }

    @Test
    void decideGateAsSystem_gateOfAnotherRun_notFound() {
        gate.setWorkflowRunId(UUID.randomUUID());

        assertThatThrownBy(
                        () -> service.decideGateAsSystem(runId, gateId, new SignalRequest("resume", null, null, null)))
                .isInstanceOf(NotFoundException.class);
        verify(nodeExecutionClaimService, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void decideGateAsSystem_signalFails_releasesTheClaim() {
        doThrow(new IllegalStateException("temporal down")).when(workflowStub).signal(anyString(), any());

        assertThatThrownBy(
                        () -> service.decideGateAsSystem(runId, gateId, new SignalRequest("resume", null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(gateId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
    }
}
