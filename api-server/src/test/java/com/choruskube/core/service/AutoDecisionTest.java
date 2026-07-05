package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.InternalUpdateNodeExecutionRequest;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.ExecutionLogRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutoDecisionTest {

    private WorkflowRunRepository runRepo;
    private NodeExecutionRepository execRepo;
    private ExecutionLogRepository logRepo;
    private GraphSnapshotBuilder snapshotBuilder;
    private RunEventPublisher eventPublisher;
    private TemplateNodeRepository templateNodeRepo;
    private NodeDefinitionRepository nodeDefinitionRepo;

    private InternalRunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private final UUID targetNodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runRepo = mock(WorkflowRunRepository.class);
        execRepo = mock(NodeExecutionRepository.class);
        logRepo = mock(ExecutionLogRepository.class);
        snapshotBuilder = mock(GraphSnapshotBuilder.class);
        eventPublisher = mock(RunEventPublisher.class);
        templateNodeRepo = mock(TemplateNodeRepository.class);
        nodeDefinitionRepo = mock(NodeDefinitionRepository.class);
        // templateNodeRepo returns empty by default → enforceOutputSpec exits early (no NPE)
        when(templateNodeRepo.findById(any())).thenReturn(Optional.empty());
        service = new InternalRunService(
                runRepo,
                execRepo,
                logRepo,
                snapshotBuilder,
                eventPublisher,
                objectMapper,
                null,
                null,
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                templateNodeRepo,
                nodeDefinitionRepo);
    }

    private NodeExecution stubExec() {
        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.running);
        exec.setGraphVersion(1);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return exec;
    }

    private void stubRunWithUnconditionalEdges() {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        String snapshot = """
                {"nodes":[
                    {"template_node_id":"%s","label":"ai_draft","executor_type":"ai","timeout_seconds":1800}
                ],"edges":[
                    {"source_node_id":"%s","target_node_id":"%s","condition":null}
                ]}""".formatted(templateNodeId, templateNodeId, targetNodeId);
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
    }

    private void stubRunWithConditionalEdges() {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        String snapshot =
                """
                {"nodes":[
                    {"template_node_id":"%s","label":"code_review","executor_type":"ai","timeout_seconds":1800}
                ],"edges":[
                    {"source_node_id":"%s","target_node_id":"%s","condition":"approved"},
                    {"source_node_id":"%s","target_node_id":"%s","condition":"rejected"}
                ]}""".formatted(templateNodeId, templateNodeId, targetNodeId, templateNodeId, UUID.randomUUID());
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
    }

    @Test
    void completedNode_unconditionalEdges_autoSetsNoDecision() {
        NodeExecution exec = stubExec();
        stubRunWithUnconditionalEdges();

        var req = new InternalUpdateNodeExecutionRequest("completed", "AI output", null, null, null, null);
        service.updateNodeExecutionStatus(runId, nodeExecId, req);

        assertThat(exec.getDecision()).isEqualTo("no_decision");
    }

    @Test
    void completedNode_conditionalEdges_doesNotAutoSetDecision() {
        NodeExecution exec = stubExec();
        stubRunWithConditionalEdges();

        var req = new InternalUpdateNodeExecutionRequest("completed", "AI output", null, null, null, null);
        service.updateNodeExecutionStatus(runId, nodeExecId, req);

        assertThat(exec.getDecision()).isNull();
    }

    @Test
    void nonCompletedStatus_doesNotAutoSetDecision() {
        NodeExecution exec = stubExec();
        when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Stub the run so publishNodeStatusChanged can retrieve organizationId
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        var req = new InternalUpdateNodeExecutionRequest("running", null, null, null, null, null);
        service.updateNodeExecutionStatus(runId, nodeExecId, req);

        assertThat(exec.getDecision()).isNull();
    }
}
