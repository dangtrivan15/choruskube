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
                null);
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

    /**
     * Mirrors the v23 self-iterating spec_review graph: a self-loop on `revised`, an
     * `approved` edge to a gate, plus a `need_human_decision:iteration_cap` escalation
     * edge — the only valid decision once the iteration cap is hit.
     */
    private void stubRunWithIterationCap(int cap) {
        String snapshot = """
                {"nodes":[
                    {"template_node_id":"%s","label":"spec_review","executor_type":"ai","timeout_seconds":1800,"iteration_cap":%d}
                ],"edges":[
                    {"source_node_id":"%s","target_node_id":"%s","condition":"revised"},
                    {"source_node_id":"%s","target_node_id":"%s","condition":"approved"},
                    {"source_node_id":"%s","target_node_id":"%s","condition":"need_human_decision:iteration_cap"}
                ]}
                """.formatted(
                        templateNodeId,
                        cap,
                        templateNodeId,
                        templateNodeId, // self-loop
                        templateNodeId,
                        otherNodeId,
                        templateNodeId,
                        otherNodeId);
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
    void getValidDecisions_belowCap_returnsAllConditions() {
        NodeExecution exec = stubExec();
        exec.setIteration(2);
        stubRunWithIterationCap(3);

        var result = service.getValidDecisions(runId, nodeExecId);

        assertThat(result).containsExactlyInAnyOrder("revised", "approved", "need_human_decision:iteration_cap");
    }

    @Test
    void getValidDecisions_atCap_returnsFullDecisionList() {
        NodeExecution exec = stubExec();
        exec.setIteration(3);
        stubRunWithIterationCap(3);

        var result = service.getValidDecisions(runId, nodeExecId);

        assertThat(result).containsExactlyInAnyOrder("revised", "approved", "need_human_decision:iteration_cap");
    }

    @Test
    void getValidDecisions_aboveCap_returnsFullDecisionList() {
        // Defensive: a stale tracker or manual replay could land us past the cap.
        NodeExecution exec = stubExec();
        exec.setIteration(7);
        stubRunWithIterationCap(3);

        var result = service.getValidDecisions(runId, nodeExecId);

        assertThat(result).containsExactlyInAnyOrder("revised", "approved", "need_human_decision:iteration_cap");
    }

    @Test
    void submitDecision_atCap_nonApproved_storesCapOverride() {
        NodeExecution exec = stubExec();
        exec.setIteration(3);
        exec.setIterationCapEpochStart(1);
        stubRunWithIterationCap(3);
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "revised");

        assertThat(result).isEqualTo("need_human_decision:iteration_cap");
        assertThat(exec.getDecision()).isEqualTo("need_human_decision:iteration_cap");
    }

    @Test
    void submitDecision_atCap_acceptsIterationCapEscalation() {
        NodeExecution exec = stubExec();
        exec.setIteration(3);
        exec.setIterationCapEpochStart(1);
        stubRunWithIterationCap(3);
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "need_human_decision:iteration_cap");

        assertThat(result).isEqualTo("need_human_decision:iteration_cap");
        assertThat(exec.getDecision()).isEqualTo("need_human_decision:iteration_cap");
    }

    @Test
    void submitDecision_atCap_approved_passesThrough() {
        NodeExecution exec = stubExec();
        exec.setIteration(3);
        exec.setIterationCapEpochStart(1);
        stubRunWithIterationCap(3);
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "approved");

        assertThat(result).isEqualTo("approved");
        assertThat(exec.getDecision()).isEqualTo("approved");
    }

    @Test
    void submitDecision_belowCap_nonApproved_storesNormally() {
        NodeExecution exec = stubExec();
        exec.setIteration(2);
        exec.setIterationCapEpochStart(1);
        stubRunWithIterationCap(3);
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "revised");

        assertThat(result).isEqualTo("revised");
        assertThat(exec.getDecision()).isEqualTo("revised");
    }

    @Test
    void submitDecision_epochReset_effectiveIterationIsOne() {
        // iteration=5, epoch_start=5: effectiveIteration=1, not capped
        NodeExecution exec = stubExec();
        exec.setIteration(5);
        exec.setIterationCapEpochStart(5);
        stubRunWithIterationCap(3);
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "revised");

        assertThat(result).isEqualTo("revised");
        assertThat(exec.getDecision()).isEqualTo("revised");
    }

    @Test
    void submitDecision_epochReset_atEffectiveCap_overrides() {
        // iteration=7, epoch_start=5: effectiveIteration=3 >= cap=3, overrides
        NodeExecution exec = stubExec();
        exec.setIteration(7);
        exec.setIterationCapEpochStart(5);
        stubRunWithIterationCap(3);
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "revised");

        assertThat(result).isEqualTo("need_human_decision:iteration_cap");
        assertThat(exec.getDecision()).isEqualTo("need_human_decision:iteration_cap");
    }

    @Test
    void submitDecision_zeroCap_doesNotOverride() {
        // iteration_cap=0 is nonsensical; the guard must prevent it from firing on every iteration
        NodeExecution exec = stubExec();
        exec.setIteration(1);
        exec.setIterationCapEpochStart(1);
        stubRunWithIterationCap(0);
        when(execRepo.save(exec)).thenReturn(exec);

        String result = service.submitDecision(runId, nodeExecId, "revised");

        assertThat(result).isEqualTo("revised");
        assertThat(exec.getDecision()).isEqualTo("revised");
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
