package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.SignalRequest;
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

/**
 * Tests for result assembly logic in RunService.signalHumanDecision().
 * Verifies that Chat Transcript and Reviewer Feedback use proper Markdown H2 headers.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceResultAssemblyTest {

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
                null, // storyRepo
                null, // epicRepo
                null,
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

    private void stubRunWithEdges(String... conditions) {
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
        when(workflowClient.newUntypedWorkflowStub("test-workflow-id")).thenReturn(workflowStub);
    }

    private String captureSignalFeedback() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), captor.capture());
        try {
            JsonNode node = objectMapper.valueToTree(captor.getValue());
            return node.get("feedback").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read feedback from signal payload", e);
        }
    }

    @Test
    void signalHumanDecision_withTranscriptAndFeedback_usesMarkdownHeaders() {
        NodeExecution exec = stubExec();
        exec.setResult("**Human:** Hello\n\n**AI:** Hi there");
        stubRunWithEdges("approved", "rejected");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", "Looks good", null, null));

        String feedback = captureSignalFeedback();
        assertThat(feedback)
                .isEqualTo(
                        "## Chat Transcript\n\n**Human:** Hello\n\n**AI:** Hi there\n\n## Reviewer Feedback\n\nLooks good");
    }

    @Test
    void signalHumanDecision_feedbackOnly_noTranscriptHeader() {
        stubExec(); // result is null
        stubRunWithEdges("approved", "rejected");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", "Please fix X", null, null));

        String feedback = captureSignalFeedback();
        assertThat(feedback).isEqualTo("## Reviewer Feedback\n\nPlease fix X");
        assertThat(feedback).doesNotContain("## Chat Transcript");
    }

    @Test
    void signalHumanDecision_noFeedback_preservesTranscriptOnly() {
        NodeExecution exec = stubExec();
        exec.setResult("some transcript");
        stubRunWithEdges("approved", "rejected");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", "", null, null));

        String feedback = captureSignalFeedback();
        // With blank feedback, the transcript is sent as-is without any headers
        assertThat(feedback).isEqualTo("some transcript");
        assertThat(feedback).doesNotContain("## Chat Transcript");
        assertThat(feedback).doesNotContain("## Reviewer Feedback");
    }

    @Test
    void signalHumanDecision_blankResult_feedbackOnly_noTranscriptHeader() {
        NodeExecution exec = stubExec();
        exec.setResult("   "); // whitespace-only result
        stubRunWithEdges("approved", "rejected");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", "Some feedback", null, null));

        String feedback = captureSignalFeedback();
        assertThat(feedback).isEqualTo("## Reviewer Feedback\n\nSome feedback");
        assertThat(feedback).doesNotContain("## Chat Transcript");
    }
}
