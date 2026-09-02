package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.EscalationContext;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the same gap {@link RunServiceCandidateBreakdownTest} covers for {@code
 * candidateBreakdown}, this time for {@code escalation}: {@code HumanGatePanel} (rendered by
 * {@code DetailPanel} on the Run Detail page) accepts an {@code escalation} prop, but {@link
 * RunService#getRun} previously never populated it on {@link
 * com.choruskube.core.dto.NodeExecutionResponse} — only {@link PendingGateService} (the Approvals
 * dashboard) did. A reviewer opening the Supervisor gate from the Run Detail page got a working
 * target picker with no indication of which node escalated or why. {@code RunService.toResponse}
 * now resolves it via the shared {@link EscalationContextResolver} — the same collaborator {@link
 * PendingGateService} uses — scoped to gate-status executions on the routing-hub node only, exactly
 * as {@code requiredArtifacts}/{@code candidateBreakdown} are scoped to gate-status executions.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceEscalationContextTest {

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
    private ArtifactResolutionService artifactResolutionService;

    @Mock
    private RoadmapCandidatesArtifactResolver roadmapCandidatesArtifactResolver;

    @Mock
    private EscalationContextResolver escalationContextResolver;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID hubNodeId = UUID.randomUUID();
    private final UUID ordinaryNodeId = UUID.randomUUID();

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
                Optional.empty(), // quotaService
                null, // placements
                null, // workflowClients
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
                artifactResolutionService,
                null,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null, // roadmapCandidateMaterializer — unused (not exercised)
                roadmapCandidatesArtifactResolver,
                null, // nodeExecutionClaimService — unused (signalHumanDecision not exercised)
                escalationContextResolver);
    }

    private WorkflowRun stubRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run.setExternalRunId("test-workflow-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    /** A Supervisor-shaped snapshot: one routing-hub node, no edges. */
    private void stubHubSnapshot() {
        String snapshot = """
                {"nodes": [{"template_node_id": "%s", "label": "Supervisor", "config_overrides": {"routing_hub": true}}], "edges": []}
                """.formatted(hubNodeId);
        when(snapshotBuilder.buildSnapshotForRun(any())).thenReturn(snapshot);
    }

    private NodeExecution stubExec(UUID templateNodeId, NodeExecutionStatus status) {
        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(status);
        exec.setGraphVersion(1);
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(exec));
        return exec;
    }

    @Test
    void supervisorGateAwaitingHuman_populatesEscalationFromResolver() {
        stubRun();
        stubHubSnapshot();
        stubExec(hubNodeId, NodeExecutionStatus.awaiting_human);
        EscalationContext escalation = new EscalationContext(
                "code_review", UUID.randomUUID(), "impl-review", "blocked_external", "CI runner is wedged");
        when(escalationContextResolver.resolve(runId)).thenReturn(escalation);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions()).hasSize(1);
        assertThat(response.nodeExecutions().get(0).escalation()).isEqualTo(escalation);
    }

    @Test
    void supervisorGateLiveChat_alsoPopulatesEscalation() {
        // live_chat is the other gate status requiredArtifacts already resolves for — escalation
        // must match that scoping, not just awaiting_human.
        stubRun();
        stubHubSnapshot();
        stubExec(hubNodeId, NodeExecutionStatus.live_chat);
        EscalationContext escalation =
                new EscalationContext("code_review", UUID.randomUUID(), "impl-review", null, null);
        when(escalationContextResolver.resolve(runId)).thenReturn(escalation);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions().get(0).escalation()).isEqualTo(escalation);
    }

    @Test
    void ordinaryGateOnNonHubNode_neverResolvesEscalation() {
        // An ordinary human-review gate (awaiting_human, but not the routing hub) must never carry
        // escalation data — that would render a nonsensical "escalated by" banner on a plain
        // approve/reject gate.
        stubRun();
        stubHubSnapshot();
        stubExec(ordinaryNodeId, NodeExecutionStatus.awaiting_human);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions().get(0).escalation()).isNull();
        verifyNoInteractions(escalationContextResolver);
    }

    @Test
    void hubNodeNotInGateStatus_neverResolvesEscalation() {
        // The Supervisor's own node, but completed rather than awaiting_human/live_chat — the
        // resolution cost (an object-storage read for escalation.md) must not be paid for every
        // execution in the run, exactly as requiredArtifacts/candidateBreakdown are scoped.
        stubRun();
        stubHubSnapshot();
        stubExec(hubNodeId, NodeExecutionStatus.completed);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions().get(0).escalation()).isNull();
        verifyNoInteractions(escalationContextResolver);
    }

    @Test
    void noRoutingHubInSnapshot_neverResolvesEscalation() {
        // A template with no Supervisor at all (the common case) — findRoutingHub returns null and
        // no execution is ever treated as the hub.
        stubRun();
        when(snapshotBuilder.buildSnapshotForRun(any())).thenReturn("{\"nodes\": [], \"edges\": []}");
        stubExec(hubNodeId, NodeExecutionStatus.awaiting_human);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions().get(0).escalation()).isNull();
        verifyNoInteractions(escalationContextResolver);
    }

    @Test
    void snapshotBuildFails_degradesToNoEscalationRatherThanFailingTheRequest() {
        // Mirrors the existing snapshot-build failure handling above this block (caught, logged,
        // snapshotJson stays null) — findRoutingHub(null) must not throw, and the Run Detail
        // request must still succeed.
        stubRun();
        when(snapshotBuilder.buildSnapshotForRun(any())).thenThrow(new RuntimeException("object storage unavailable"));
        stubExec(hubNodeId, NodeExecutionStatus.awaiting_human);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions().get(0).escalation()).isNull();
        verifyNoInteractions(escalationContextResolver);
    }
}
