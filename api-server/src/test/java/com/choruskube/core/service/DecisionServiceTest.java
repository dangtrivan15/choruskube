package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.ExecutionLogRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DecisionServiceTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private ExecutionLogRepository logRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private RunEventPublisher eventPublisher;

    private InternalRunService service;

    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private final UUID otherNodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new InternalRunService(
                runRepo,
                execRepo,
                logRepo,
                snapshotBuilder,
                eventPublisher,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null, // taskRepo
                null, // epicRepo
                null,
                new DecisionOptionsResolver());
    }

    private NodeExecution stubExec() {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setGraphVersion(1);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        return exec;
    }

    private void stubRunWithConditionalEdges() {
        String snapshot = """
                {"nodes":[
                    {"template_node_id":"%s","label":"code_review","executor_type":"ai","timeout_seconds":1800}
                ],"edges":[
                    {"source_node_id":"%s","target_node_id":"%s","condition":"approved"},
                    {"source_node_id":"%s","target_node_id":"%s","condition":"rejected"}
                ]}
                """.formatted(templateNodeId, templateNodeId, otherNodeId, templateNodeId, otherNodeId);
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
    }

    private void stubRunWithUnconditionalEdgesOnly() {
        String snapshot = """
                {"nodes":[
                    {"template_node_id":"%s","label":"code_review","executor_type":"ai","timeout_seconds":1800}
                ],"edges":[
                    {"source_node_id":"%s","target_node_id":"%s","condition":null}
                ]}
                """.formatted(templateNodeId, templateNodeId, otherNodeId);
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
    }

    @Test
    void submitDecision_validDecision_storesAndReturns() {
        NodeExecution exec = stubExec();
        stubRunWithConditionalEdges();
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "approved");

        assertThat(result).isEqualTo("approved");
        assertThat(exec.getDecision()).isEqualTo("approved");
        verify(execRepo).save(exec);
    }

    @Test
    void submitDecision_invalidDecision_throwsWithValidOptions() {
        stubExec();
        stubRunWithConditionalEdges();

        assertThatThrownBy(() -> service.submitDecision(runId, nodeExecId, "looks good"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("approved")
                .hasMessageContaining("rejected");
    }

    @Test
    void submitDecision_noConditionalEdges_throws() {
        stubExec();
        stubRunWithUnconditionalEdgesOnly();

        assertThatThrownBy(() -> service.submitDecision(runId, nodeExecId, "approved"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no conditional edges");
    }

    @Test
    void getDecision_returnsStoredDecision() {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setDecision("rejected");
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));

        String result = service.getDecision(runId, nodeExecId);

        assertThat(result).isEqualTo("rejected");
    }

    @Test
    void getDecision_returnsNullWhenNotSet() {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));

        String result = service.getDecision(runId, nodeExecId);

        assertThat(result).isNull();
    }
}
