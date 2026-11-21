package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.service.RunScopeBinder;
import com.choruskube.core.service.RunService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * E2E tests for the human gate decision flow, verifying:
 * - signalHumanDecision does NOT persist the decision (orchestrator does it later)
 * - submitDecision (internal endpoint) persists the decision independently
 */
public class HumanGateDecisionFlowTest extends SharedContextTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GateStampRepository gateStampRepo;

    @Autowired
    private RunService runService;

    @Autowired
    private RunScopeBinder runScopeBinder;

    private WorkflowStub mockStub;

    @BeforeEach
    void setUp() {
        mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
        Mockito.when(workflowClient.newUntypedWorkflowStub(ArgumentMatchers.anyString()))
                .thenReturn(mockStub);
    }

    /**
     * Creates a run from the seeded "Feature Development" template and returns
     * the run ID and a human node's template_node_id (approve_spec_and_plan).
     */
    private record RunWithHumanNode(UUID runId, UUID humanTemplateNodeId) {}

    private RunWithHumanNode createRunWithHumanGate() throws Exception {
        GitRepo gitRepo = new GitRepo();
        String humanGateUrl = "https://github.com/e2e-human-gate/" + UUID.randomUUID();
        gitRepo.setUrl(humanGateUrl);
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName(humanGateUrl));
        gitRepo.setTestCommand("npm test");
        gitRepo.setAgentImage("my-agent:latest");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        var template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("feature_request", "Test human gate flow");
        inputs.put("software_project_id", gitRepo.getId().toString());

        Map<String, Object> body = Map.of("graphTemplateId", template.getId(), "inputs", inputs);

        MvcResult result = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode runJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID runId = UUID.fromString(runJson.get("id").asText());

        // Find the approve_spec_and_plan node from the snapshot
        JsonNode snapshot = runJson.get("graphSnapshot");
        UUID humanNodeId = null;
        for (JsonNode n : snapshot.get("nodes")) {
            if ("approve_spec_and_plan".equals(n.get("label").asText())) {
                humanNodeId = UUID.fromString(n.get("template_node_id").asText());
                break;
            }
        }
        assertThat(humanNodeId)
                .as("approve_spec_and_plan node should exist in template")
                .isNotNull();

        return new RunWithHumanNode(runId, humanNodeId);
    }

    private UUID createAwaitingHumanExec(RunWithHumanNode run) {
        return createAwaitingHumanExec(run, 1);
    }

    private UUID createAwaitingHumanExec(RunWithHumanNode run, int iteration) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.runId());
        exec.setTemplateNodeId(run.humanTemplateNodeId());
        exec.setGraphVersion(1);
        exec.setIteration(iteration);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setArtifactRefs("{}");
        exec.setFinalizeMode("async");
        return execRepo.save(exec).getId();
    }

    @Test
    void signal_feedbackOverCap_rejectedWith400AndGateLeftOpen() throws Exception {
        var run = createRunWithHumanGate();
        UUID execId = createAwaitingHumanExec(run);

        Map<String, String> signalBody = Map.of("decision", "approved", "feedback", "a".repeat(20001));

        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/nodes/" + execId + "/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signalBody)))
                .andExpect(status().isBadRequest());

        // Refused by request-body validation before the handler runs: the gate is left exactly
        // as it was, with no claim, no gate stamp and no signal.
        NodeExecution reloaded = execRepo.findById(execId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NodeExecutionStatus.awaiting_human);
        assertThat(reloaded.getDecision()).isNull();
        assertThat(gateStampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(run.runId()))
                .isEmpty();
        Mockito.verify(mockStub, Mockito.never()).signal(ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    void signal_feedbackAtCapNullOrEmpty_accepted() throws Exception {
        var run = createRunWithHumanGate();

        String feedback20000 = "b".repeat(20000);
        UUID execAtCap = createAwaitingHumanExec(run);
        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/nodes/" + execAtCap + "/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "approved", "feedback", feedback20000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));
        ArgumentCaptor<Object> captorAtCap = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockStub).signal(ArgumentMatchers.eq("human-decision-" + execAtCap), captorAtCap.capture());
        assertThat(objectMapper
                        .valueToTree(captorAtCap.getValue())
                        .get("feedback")
                        .asText())
                .isEqualTo(feedback20000);

        Map<String, String> nullFeedbackBody = new HashMap<>();
        nullFeedbackBody.put("decision", "approved");
        nullFeedbackBody.put("feedback", null);
        UUID execNull = createAwaitingHumanExec(run, 2);
        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/nodes/" + execNull + "/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nullFeedbackBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));

        UUID execEmpty = createAwaitingHumanExec(run, 3);
        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/nodes/" + execEmpty + "/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", "approved", "feedback", ""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));
    }

    @Test
    void decideGateAsSystem_signalsOversizedFeedbackWhole() throws Exception {
        var run = createRunWithHumanGate();
        UUID execId = createAwaitingHumanExec(run);

        // Built in the same shape RunService#recheckStoryVersion builds its guidance: a fixed
        // guidance line, then the re-check's reviewer-note header, then 20000 characters of
        // reviewer feedback — storyVersionGuidance itself is package-private to
        // com.choruskube.core.service and cannot be called from this package.
        String fixedGuidanceLine = "The Story spec moved from v1 to v2 since this Task planned. Read the Revisions"
                + " entries for v2 through v2 in /workspace/in/run_input/story_spec.md and check this change"
                + " against them.";
        String reviewerNoteHeader = "\n\n## Reviewer's note with the approval\n\n";
        String oversizedFeedback = fixedGuidanceLine + reviewerNoteHeader + "x".repeat(20000);
        assertThat(oversizedFeedback.length()).isGreaterThan(20000);

        SignalRequest request = new SignalRequest("redraft", oversizedFeedback, null, null);
        runScopeBinder.runInScopeOf(run.runId(), () -> runService.decideGateAsSystem(run.runId(), execId, request));

        // A request the system builds for itself never passes through @Valid, so the whole,
        // over-cap text reaches the Temporal signal unmodified.
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockStub).signal(ArgumentMatchers.eq("human-decision-" + execId), captor.capture());
        assertThat(objectMapper.valueToTree(captor.getValue()).get("feedback").asText())
                .isEqualTo(oversizedFeedback);
    }

    @Test
    void signalHumanDecision_doesNotPersistDecisionInDB() throws Exception {
        var run = createRunWithHumanGate();

        // Create a node execution in awaiting_human state (simulating orchestrator behavior).
        // approve_spec_and_plan declares drift-recheck work, so an "approved" decision only
        // proceeds here because the gate is marked async-finalizable.
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.runId());
        exec.setTemplateNodeId(run.humanTemplateNodeId());
        exec.setGraphVersion(1);
        exec.setIteration(1);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setArtifactRefs("{}");
        exec.setFinalizeMode("async");
        exec = execRepo.save(exec);
        UUID execId = exec.getId();

        // Signal the human decision via the public API
        Map<String, String> signalBody = Map.of("decision", "approved", "feedback", "Looks great");

        mockMvc.perform(post("/api/v1/runs/" + run.runId() + "/nodes/" + execId + "/signal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(signalBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));

        // Verify: decision is NOT persisted in DB (it lands only once finalize's complete step runs)
        NodeExecution reloaded = execRepo.findById(execId).orElseThrow();
        assertThat(reloaded.getDecision())
                .as("Decision should NOT be persisted by signalHumanDecision — finalize's complete step writes it")
                .isNull();

        // Verify: the Temporal signal WAS sent with the correct payload
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(mockStub).signal(org.mockito.ArgumentMatchers.eq("human-decision-" + execId), captor.capture());
        com.fasterxml.jackson.databind.JsonNode signalPayload = objectMapper.valueToTree(captor.getValue());
        assertThat(signalPayload.get("decision").asText()).isEqualTo("approved");
        assertThat(signalPayload.get("feedback").asText()).contains("Looks great");
    }

    @Test
    void submitDecision_persistsDecisionIndependently() throws Exception {
        var run = createRunWithHumanGate();

        // Create a node execution (simulating orchestrator creating a retry execution)
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.runId());
        exec.setTemplateNodeId(run.humanTemplateNodeId());
        exec.setGraphVersion(1);
        exec.setIteration(1);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setArtifactRefs("{}");
        exec = execRepo.save(exec);
        UUID execId = exec.getId();

        // Orchestrator calls PUT /internal/.../decision to persist the decision
        Map<String, String> decisionBody = Map.of("decision", "approved");

        mockMvc.perform(put("/internal/runs/" + run.runId() + "/node-executions/" + execId + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(decisionBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("approved"));

        // Verify: decision IS persisted in DB by the internal endpoint
        NodeExecution reloaded = execRepo.findById(execId).orElseThrow();
        assertThat(reloaded.getDecision()).isEqualTo("approved");
    }

    @Test
    void submitDecision_thenGetDecision_roundTrips() throws Exception {
        var run = createRunWithHumanGate();

        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.runId());
        exec.setTemplateNodeId(run.humanTemplateNodeId());
        exec.setGraphVersion(1);
        exec.setIteration(1);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setArtifactRefs("{}");
        exec = execRepo.save(exec);
        UUID execId = exec.getId();

        // GET decision before submit — should be null
        mockMvc.perform(get("/internal/runs/" + run.runId() + "/node-executions/" + execId + "/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").doesNotExist());

        // PUT decision (v23: Approve Spec & Plan rejection became `redraft`)
        mockMvc.perform(put("/internal/runs/" + run.runId() + "/node-executions/" + execId + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", "redraft"))))
                .andExpect(status().isOk());

        // GET decision after submit — should be "redraft"
        mockMvc.perform(get("/internal/runs/" + run.runId() + "/node-executions/" + execId + "/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("redraft"));
    }
}
