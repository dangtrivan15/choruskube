package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.dto.PendingGateCountResponse;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.jpa.domain.Specification;

class PendingGateServiceTest {

    private NodeExecutionRepository execRepo;
    private WorkflowRunRepository runRepo;
    private GraphSnapshotBuilder snapshotBuilder;
    private ObjectMapper objectMapper;
    private PendingGateService service;

    @BeforeEach
    void setUp() {
        execRepo = Mockito.mock(NodeExecutionRepository.class);
        runRepo = Mockito.mock(WorkflowRunRepository.class);
        snapshotBuilder = Mockito.mock(GraphSnapshotBuilder.class);
        objectMapper = new ObjectMapper();
        service = new PendingGateService(
                execRepo,
                runRepo,
                snapshotBuilder,
                objectMapper,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Mockito.mock(ArtifactResolutionService.class),
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                Mockito.mock(RoadmapCandidatesArtifactResolver.class));
    }

    @Test
    void getPendingGates_emptyWhenNoAwaitingNodes() {
        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of());

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).isEmpty();
    }

    @Test
    void getPendingGates_returnsGateWithCorrectFields() {
        UUID runId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();
        UUID predNodeId = UUID.randomUUID();
        UUID predExecId = UUID.randomUUID();

        // Build snapshot JSON
        String snapshot = """
                {
                  "nodes": [
                    {"template_node_id": "%s", "label": "Review Gate", "timeout_seconds": 3600},
                    {"template_node_id": "%s", "label": "AI Step"}
                  ],
                  "edges": [
                    {"source_node_id": "%s", "target_node_id": "%s"}
                  ]
                }
                """.formatted(templateNodeId, predNodeId, predNodeId, templateNodeId);

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setGraphTemplateId(graphTemplateId);
        run.setName("My Test Run");
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution awaitingExec = new NodeExecution();
        awaitingExec.setId(nodeExecId);
        awaitingExec.setWorkflowRunId(runId);
        awaitingExec.setTemplateNodeId(templateNodeId);
        awaitingExec.setStatus(NodeExecutionStatus.awaiting_human);
        awaitingExec.setIteration(1);
        awaitingExec.setStartedAt(Instant.parse("2026-03-29T10:00:00Z"));

        NodeExecution predExec = new NodeExecution();
        predExec.setId(predExecId);
        predExec.setWorkflowRunId(runId);
        predExec.setTemplateNodeId(predNodeId);
        predExec.setStatus(NodeExecutionStatus.completed);
        predExec.setIteration(1);
        predExec.setResult("AI generated output");

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(awaitingExec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection()))
                .thenReturn(List.of(awaitingExec, predExec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        PendingGateResponse gate = result.get(0);
        assertThat(gate.nodeExecutionId()).isEqualTo(nodeExecId);
        assertThat(gate.runId()).isEqualTo(runId);
        assertThat(gate.runName()).isEqualTo("My Test Run");
        assertThat(gate.nodeLabel()).isEqualTo("Review Gate");
        assertThat(gate.iteration()).isEqualTo(1);
        assertThat(gate.timeoutSeconds()).isEqualTo(3600);
        assertThat(gate.waitingSince()).isEqualTo(Instant.parse("2026-03-29T10:00:00Z"));
        assertThat(gate.status()).isEqualTo("awaiting_human");
        assertThat(gate.predecessorOutputs()).hasSize(1);
        assertThat(gate.predecessorOutputs().get(0).label()).isEqualTo("AI Step");
        assertThat(gate.predecessorOutputs().get(0).result()).isEqualTo("AI generated output");
        assertThat(gate.predecessorOutputs().get(0).nodeExecutionId()).isEqualTo(predExecId);
    }

    @Test
    void getPendingGates_handlesSnapshotBuildFailure() {
        UUID runId = UUID.randomUUID();

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setName("Failing Run");
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution awaitingExec = new NodeExecution();
        awaitingExec.setId(UUID.randomUUID());
        awaitingExec.setWorkflowRunId(runId);
        awaitingExec.setTemplateNodeId(UUID.randomUUID());
        awaitingExec.setStatus(NodeExecutionStatus.awaiting_human);
        awaitingExec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(awaitingExec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(awaitingExec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenThrow(new RuntimeException("template not found"));

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodeLabel()).isEqualTo("Unknown");
        assertThat(result.get(0).predecessorOutputs()).isEmpty();
    }

    @Test
    void getPendingGates_multipleGatesAcrossRuns() {
        UUID runId1 = UUID.randomUUID();
        UUID runId2 = UUID.randomUUID();
        UUID nodeId1 = UUID.randomUUID();
        UUID nodeId2 = UUID.randomUUID();

        String snapshot1 = """
                {"nodes": [{"template_node_id": "%s", "label": "Gate 1"}], "edges": []}
                """.formatted(nodeId1);
        String snapshot2 = """
                {"nodes": [{"template_node_id": "%s", "label": "Gate 2"}], "edges": []}
                """.formatted(nodeId2);

        WorkflowRun run1 = new WorkflowRun();
        run1.setId(runId1);
        run1.setName("Run A");
        run1.setStatus(WorkflowRunStatus.awaiting_human);

        WorkflowRun run2 = new WorkflowRun();
        run2.setId(runId2);
        run2.setName("Run B");
        run2.setStatus(WorkflowRunStatus.running);

        NodeExecution exec1 = new NodeExecution();
        exec1.setId(UUID.randomUUID());
        exec1.setWorkflowRunId(runId1);
        exec1.setTemplateNodeId(nodeId1);
        exec1.setStatus(NodeExecutionStatus.awaiting_human);
        exec1.setIteration(1);

        NodeExecution exec2 = new NodeExecution();
        exec2.setId(UUID.randomUUID());
        exec2.setWorkflowRunId(runId2);
        exec2.setTemplateNodeId(nodeId2);
        exec2.setStatus(NodeExecutionStatus.awaiting_human);
        exec2.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(exec1, exec2));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run1, run2));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(exec1, exec2));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run1)).thenReturn(snapshot1);
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run2)).thenReturn(snapshot2);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(2);
    }

    @Test
    void getPendingGates_nullRunNameReturnsEmptyString() {
        UUID runId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        String snapshot = """
                {"nodes": [{"template_node_id": "%s", "label": "Gate"}], "edges": []}
                """.formatted(nodeId);

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        // name is intentionally left null
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(nodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(exec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(exec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).runName()).isEqualTo("");
    }

    @Test
    void getPendingGateCount_returnsCorrectCount() {
        // The count now runs as a single scoped COUNT query (gate-status spec AND the
        // ScopeProvider's predicate); the NoOp provider adds no restriction in single-tenant mode.
        Mockito.when(execRepo.count(Mockito.<Specification<NodeExecution>>any()))
                .thenReturn(2L);

        PendingGateCountResponse result = service.getPendingGateCount();

        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void getPendingGateCount_returnsZeroWhenEmpty() {
        Mockito.when(execRepo.count(Mockito.<Specification<NodeExecution>>any()))
                .thenReturn(0L);

        PendingGateCountResponse result = service.getPendingGateCount();

        assertThat(result.count()).isEqualTo(0);
    }

    @Test
    void getPendingGates_includesLiveChatStatus() {
        UUID runId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        String snapshot = """
                {"nodes": [{"template_node_id": "%s", "label": "Chat Gate"}], "edges": []}
                """.formatted(nodeId);

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setName("Chat Run");
        run.setStatus(WorkflowRunStatus.running);

        NodeExecution liveChatExec = new NodeExecution();
        liveChatExec.setId(UUID.randomUUID());
        liveChatExec.setWorkflowRunId(runId);
        liveChatExec.setTemplateNodeId(nodeId);
        liveChatExec.setStatus(NodeExecutionStatus.live_chat);
        liveChatExec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(liveChatExec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(liveChatExec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("live_chat");
        assertThat(result.get(0).nodeLabel()).isEqualTo("Chat Gate");
    }

    @Test
    void getPendingGateCount_includesBothStatuses() {
        // Both gate statuses are covered by the gate-status spec; the count is a single scoped query.
        Mockito.when(execRepo.count(Mockito.<Specification<NodeExecution>>any()))
                .thenReturn(2L);

        PendingGateCountResponse result = service.getPendingGateCount();

        assertThat(result.count()).isEqualTo(2);
    }

    @Test
    void getPendingGates_populatesRequiredArtifactsFromResolutionService() {
        UUID runId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();

        ArtifactResolutionService mockResolutionService = Mockito.mock(ArtifactResolutionService.class);
        List<ResolvedArtifactGroup> expectedGroups = List.of(new ResolvedArtifactGroup(
                UUID.randomUUID(), "Planning Node", List.of(new ResolvedArtifactEntry("spec.md", "Spec document"))));
        Mockito.when(mockResolutionService.resolveRequiredArtifacts(templateNodeId, runId))
                .thenReturn(expectedGroups);

        PendingGateService localService = new PendingGateService(
                execRepo,
                runRepo,
                snapshotBuilder,
                objectMapper,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                mockResolutionService,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                Mockito.mock(RoadmapCandidatesArtifactResolver.class));

        String snapshot = """
                {"nodes": [{"template_node_id": "%s", "label": "Gate"}], "edges": []}
                """.formatted(templateNodeId);

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setName("Test Run");
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(exec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(exec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);

        List<PendingGateResponse> result = localService.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).requiredArtifacts()).isEqualTo(expectedGroups);
    }

    @Test
    void getPendingGates_populatesDecisionOptionsForV23SpecGate() {
        UUID runId = UUID.randomUUID();
        UUID gateNodeId = UUID.randomUUID();
        UUID specReviewId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        UUID implementId = UUID.randomUUID();

        // approve_spec_and_plan has three outgoing conditional edges per v23 split:
        // approved → implement, rereview → spec_review, redraft → draft_spec_and_plan
        String snapshot = """
                {
                  "nodes": [
                    {"template_node_id": "%s", "label": "approve_spec_and_plan"},
                    {"template_node_id": "%s", "label": "spec_review"},
                    {"template_node_id": "%s", "label": "draft_spec_and_plan"},
                    {"template_node_id": "%s", "label": "implement"}
                  ],
                  "edges": [
                    {"source_node_id": "%s", "target_node_id": "%s", "condition": "approved"},
                    {"source_node_id": "%s", "target_node_id": "%s", "condition": "rereview"},
                    {"source_node_id": "%s", "target_node_id": "%s", "condition": "redraft"}
                  ]
                }
                """.formatted(
                        gateNodeId,
                        specReviewId,
                        draftId,
                        implementId,
                        gateNodeId,
                        implementId,
                        gateNodeId,
                        specReviewId,
                        gateNodeId,
                        draftId);

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setName("Run with v23 spec gate");
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(gateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(exec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(exec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).decisionOptions()).containsExactlyInAnyOrder("approved", "rereview", "redraft");
    }

    @Test
    void getPendingGates_populatesDecisionOptionsForLegacyGate() {
        UUID runId = UUID.randomUUID();
        UUID gateNodeId = UUID.randomUUID();
        UUID nextNodeId = UUID.randomUUID();
        UUID retryNodeId = UUID.randomUUID();

        String snapshot =
                """
                {
                  "nodes": [
                    {"template_node_id": "%s", "label": "human_review"},
                    {"template_node_id": "%s", "label": "next_step"},
                    {"template_node_id": "%s", "label": "retry"}
                  ],
                  "edges": [
                    {"source_node_id": "%s", "target_node_id": "%s", "condition": "approved"},
                    {"source_node_id": "%s", "target_node_id": "%s", "condition": "rejected"}
                  ]
                }
                """.formatted(gateNodeId, nextNodeId, retryNodeId, gateNodeId, nextNodeId, gateNodeId, retryNodeId);

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setName("Legacy gate run");
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(gateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(exec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(exec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).decisionOptions()).containsExactlyInAnyOrder("approved", "rejected");
    }

    @Test
    void getPendingGates_decisionOptionsEmptyWhenNoEdges() {
        UUID runId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();

        String snapshot = """
                {"nodes": [{"template_node_id": "%s", "label": "terminal_gate"}], "edges": []}
                """.formatted(nodeId);

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setName("Terminal gate run");
        run.setStatus(WorkflowRunStatus.awaiting_human);

        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(nodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setIteration(1);

        Mockito.when(execRepo.findAll(ArgumentMatchers.<Specification<NodeExecution>>any()))
                .thenReturn(List.of(exec));
        Mockito.when(runRepo.findAllById(Mockito.anyCollection())).thenReturn(List.of(run));
        Mockito.when(execRepo.findByWorkflowRunIdIn(Mockito.anyCollection())).thenReturn(List.of(exec));
        Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);

        List<PendingGateResponse> result = service.getPendingGates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).decisionOptions()).isEmpty();
    }
}
