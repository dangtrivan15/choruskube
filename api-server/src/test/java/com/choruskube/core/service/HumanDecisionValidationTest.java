package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
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

@ExtendWith(MockitoExtension.class)
class HumanDecisionValidationTest {

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
    private NodeExecutionClaimService nodeExecutionClaimService;

    @Mock
    private WorkflowClientRegistry workflowClients;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(workflowClients.clientFor(any())).thenReturn(workflowClient);
        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                objectMapper,
                eventPublisher,
                gitRepoRepo,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(), // quotaService
                null, // placements
                workflowClients,
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
                null,
                null,
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
        lenient().when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient()
                .when(nodeExecutionClaimService.compareAndSetStatus(any(), any(), any()))
                .thenReturn(1);
        return exec;
    }

    private WorkflowRun stubRun(String... conditions) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("test-workflow-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        StringBuilder edges = new StringBuilder();
        for (int i = 0; i < conditions.length; i++) {
            if (i > 0) edges.append(",");
            edges.append("""
                {"source_node_id":"%s","target_node_id":"%s","condition":"%s"}""".formatted(templateNodeId, UUID.randomUUID(), conditions[i]));
        }
        String snapshot = """
                {"nodes":[{"template_node_id":"%s","label":"human_review","executor_type":"human","timeout_seconds":86400}],
                 "edges":[%s]}""".formatted(templateNodeId, edges);
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
        lenient()
                .when(workflowClient.newUntypedWorkflowStub("test-workflow-id"))
                .thenReturn(workflowStub);
        return run;
    }

    @Test
    void signalHumanDecision_validDecision_storesDecisionAndSignalsWithFeedback() {
        NodeExecution exec = stubExec();
        stubRun("approved", "rejected");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", "LGTM", null, null));

        // Decision is NOT written to DB here — orchestrator persists it via SetNodeDecision activity
        assertThat(exec.getDecision()).isNull();
        // Result is NOT written to DB — orchestrator handles that via the signal
        assertThat(exec.getResult()).isNull();

        // Verify signal carries the assembled feedback with Markdown header
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), captor.capture());
        JsonNode payloadNode = objectMapper.valueToTree(captor.getValue());
        assertThat(payloadNode.get("feedback").asText()).isEqualTo("## Reviewer Feedback\n\nLGTM");
    }

    @Test
    void signalHumanDecision_invalidDecision_throws() {
        stubExec();
        stubRun("approved", "rejected");

        assertThatThrownBy(() -> service.signalHumanDecision(
                        runId, nodeExecId, new SignalRequest("maybe", "not sure", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid decision");
    }

    @Test
    void signalHumanDecision_appendsFeedbackToExistingResult() {
        NodeExecution exec = stubExec();
        exec.setResult("Previous AI output");
        stubRun("approved", "rejected");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", "Looks good", null, null));

        // Result in DB is unchanged — only decision is stored
        assertThat(exec.getResult()).isEqualTo("Previous AI output");

        // Signal carries the assembled result with Markdown headers (transcript + feedback)
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), captor.capture());
        String feedback =
                objectMapper.valueToTree(captor.getValue()).get("feedback").asText();
        assertThat(feedback).startsWith("## Chat Transcript\n\n");
        assertThat(feedback).contains("Previous AI output");
        assertThat(feedback).contains("## Reviewer Feedback\n\n");
        assertThat(feedback).contains("Looks good");
        assertThat(feedback)
                .isEqualTo("## Chat Transcript\n\nPrevious AI output\n\n## Reviewer Feedback\n\nLooks good");
    }

    @Test
    void signalHumanDecision_nullFeedback_doesNotModifyResult() {
        NodeExecution exec = stubExec();
        stubRun("approved", "rejected");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        // Decision is NOT written to DB here — orchestrator persists it via SetNodeDecision activity
        assertThat(exec.getDecision()).isNull();
        assertThat(exec.getResult()).isNull();
    }
}
