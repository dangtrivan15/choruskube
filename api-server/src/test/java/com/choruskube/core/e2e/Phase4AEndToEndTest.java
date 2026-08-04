package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
public class Phase4AEndToEndTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository workflowRunRepo;

    @Autowired
    private EpicRepository epicRepo;

    @MockitoBean
    private io.temporal.serviceclient.WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @AfterEach
    void cleanUpRuns() {
        // Each test in this class commits WorkflowRun rows to the shared TestContainers
        // database. Without cleanup, those rows pollute tests like AnalyticsControllerTest
        // that assert on an empty database. workflow_run.task_id has a plain (non-cascading) FK
        // on task.id, so workflow_run rows must be deleted BEFORE epic — deleting epic first
        // would try to cascade-delete a still-referenced task and violate that FK. Clearing epic
        // alone (after runs are gone) suffices to clear the whole Epic -> Story -> Task chain
        // (none today, but future-proof): story/task cascade via the migration's ON DELETE
        // CASCADE FKs.
        workflowRunRepo.deleteAll();
        epicRepo.deleteAll();
    }

    @BeforeEach
    void setUp() {
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void v1TemplateIsSeededWithInputSchema() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/graph-templates"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode page = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode templates = page.get("content");
        JsonNode v1 = null;
        for (JsonNode t : templates) {
            if ("Feature Development".equals(t.get("name").asText())) {
                v1 = t;
                break;
            }
        }
        assertThat(v1).as("Feature Development template should be seeded").isNotNull();

        JsonNode schema = v1.get("inputSchema");
        assertThat(schema.isArray()).isTrue();
        assertThat(schema).hasSize(2);

        Set<String> fieldNames = new HashSet<>();
        for (JsonNode field : schema) {
            fieldNames.add(field.get("name").asText());
        }
        assertThat(fieldNames).containsExactlyInAnyOrder("software_project_id", "feature_request");

        // Verify "software_project_id" field uses the software_project_id input type
        String spType = null;
        for (JsonNode field : schema) {
            if ("software_project_id".equals(field.get("name").asText())) {
                spType = field.get("type").asText();
            }
        }
        assertThat(spType).isEqualTo("software_project_id");
    }

    @Test
    void startRunWithMissingInputsReturns400() throws Exception {
        var template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();

        // No inputs — all 4 required fields missing
        Map<String, Object> body = Map.of("graphTemplateId", template.getId());

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startRunWithPartialInputsReturns400() throws Exception {
        var template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();

        // Send inputs missing the required feature_request field
        Map<String, Object> body = Map.of(
                "graphTemplateId", template.getId(),
                "inputs", Map.of());

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startRunWithValidInputsCreatesRunWithEmbeddedInputs() throws Exception {
        // Create GitRepo for tests
        GitRepo gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/e2e-valid-inputs/repo");
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/e2e-valid-inputs/repo"));
        gitRepo.setTestCommand("npm test");
        gitRepo.setAgentImage("my-agent:latest");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        var template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("feature_request", "Add a health check endpoint");
        inputs.put("software_project_id", gitRepo.getId().toString());

        Map<String, Object> body = Map.of("graphTemplateId", template.getId(), "inputs", inputs);

        MvcResult result = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateName").value("Feature Development"))
                .andExpect(jsonPath("$.status").value("pending"))
                .andReturn();

        // Verify the on-demand graph snapshot in the API response
        JsonNode runJson = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode snapshot = runJson.get("graphSnapshot");
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.has("inputs")).isTrue();
        assertThat(snapshot.get("inputs").get("repo_url").asText())
                .isEqualTo("https://github.com/e2e-valid-inputs/repo");
        assertThat(snapshot.get("inputs").get("test_command").asText()).isEqualTo("npm test");
        assertThat(snapshot.get("inputs").get("agent_image").asText()).isEqualTo("my-agent:latest");

        // Verify snapshot shape (v32: 9 nodes, 20 edges — Code Review's escalation
        // exits now route through the Review Escalation human gate before Test).
        assertThat(snapshot.get("nodes")).hasSize(9);
        assertThat(snapshot.get("edges")).hasSize(20);
    }

    @Test
    void transitivePredecessorsOnV1Graph() throws Exception {
        // Create GitRepo for tests
        GitRepo gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/e2e-predecessors/repo");
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/e2e-predecessors/repo"));
        gitRepo.setTestCommand("npm test");
        gitRepo.setAgentImage("my-agent:latest");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        var template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("feature_request", "Add a health check endpoint");
        inputs.put("software_project_id", gitRepo.getId().toString());

        Map<String, Object> body = Map.of("graphTemplateId", template.getId(), "inputs", inputs);

        MvcResult runResult = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode runJson = objectMapper.readTree(runResult.getResponse().getContentAsString());
        UUID runId = UUID.fromString(runJson.get("id").asText());

        // Find template node IDs by label from the API response's on-demand snapshot
        MvcResult runDetailResult = mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode runDetail = objectMapper.readTree(runDetailResult.getResponse().getContentAsString());
        JsonNode snapshot = runDetail.get("graphSnapshot");
        Map<String, UUID> labelToNodeId = new HashMap<>();
        for (JsonNode n : snapshot.get("nodes")) {
            labelToNodeId.put(
                    n.get("label").asText(),
                    UUID.fromString(n.get("template_node_id").asText()));
        }

        // Simulate completed executions for nodes 1-3 (Draft Spec & Plan through Approve Spec & Plan)
        String[] completedLabels = {"draft_spec_and_plan", "spec_review", "approve_spec_and_plan"};
        for (String label : completedLabels) {
            NodeExecution exec = new NodeExecution();
            exec.setWorkflowRunId(runId);
            exec.setTemplateNodeId(labelToNodeId.get(label));
            exec.setGraphVersion(1);
            exec.setIteration(1);
            exec.setStatus(NodeExecutionStatus.completed);
            exec.setResult("output from " + label);
            exec.setArtifactRefs("{}");
            execRepo.save(exec);
        }

        // Create a pending execution for Implement (node 4) — this is the one we query
        NodeExecution implementExec = new NodeExecution();
        implementExec.setWorkflowRunId(runId);
        implementExec.setTemplateNodeId(labelToNodeId.get("implement"));
        implementExec.setGraphVersion(1);
        implementExec.setIteration(1);
        implementExec.setStatus(NodeExecutionStatus.pending);
        implementExec = execRepo.save(implementExec);

        // Query transitive predecessors for Implement node
        MvcResult predResult = mockMvc.perform(
                        get("/internal/runs/" + runId + "/node-executions/" + implementExec.getId() + "/predecessors"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode predecessors = objectMapper.readTree(predResult.getResponse().getContentAsString());

        // Implement's transitive predecessors: all 3 completed nodes
        assertThat(predecessors).hasSize(3);

        Set<String> predLabels = new HashSet<>();
        for (JsonNode pred : predecessors) {
            predLabels.add(pred.get("label").asText());
        }
        assertThat(predLabels).containsExactlyInAnyOrder("draft_spec_and_plan", "spec_review", "approve_spec_and_plan");
    }

    @Test
    void startRunSetsGraphSourceTemplateId() throws Exception {
        // Create GitRepo for tests
        GitRepo gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/e2e-source-template/repo");
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/e2e-source-template/repo"));
        gitRepo.setTestCommand("npm test");
        gitRepo.setAgentImage("my-agent:latest");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        var template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();

        Map<String, Object> inputs = new HashMap<>();
        inputs.put("feature_request", "Test graph source pinning");
        inputs.put("software_project_id", gitRepo.getId().toString());

        Map<String, Object> body = Map.of("graphTemplateId", template.getId(), "inputs", inputs);

        MvcResult result = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode runJson = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID runId = UUID.fromString(runJson.get("id").asText());

        // Verify snapshot node IDs match the template's actual node IDs
        var run = mockMvc.perform(get("/api/v1/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode runDetail = objectMapper.readTree(run.getResponse().getContentAsString());
        JsonNode snapshot = runDetail.get("graphSnapshot");

        Set<String> snapshotNodeIds = new HashSet<>();
        for (JsonNode n : snapshot.get("nodes")) {
            snapshotNodeIds.add(n.get("template_node_id").asText());
        }

        // The snapshot node IDs should match the template's actual template_node rows
        var templateNodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        Set<String> actualNodeIds = new HashSet<>();
        for (var tn : templateNodes) {
            actualNodeIds.add(tn.getId().toString());
        }

        assertThat(snapshotNodeIds).isEqualTo(actualNodeIds);
    }

    @Test
    void githubTokenEndpointReturnsErrorWhenNotConfigured() throws Exception {
        // The scoped endpoint exists but no GitHub credential is configured — expect 404
        UUID fakeRunId = UUID.randomUUID();
        UUID fakeExecId = UUID.randomUUID();
        mockMvc.perform(get("/internal/runs/" + fakeRunId + "/node-executions/" + fakeExecId + "/github-token"))
                .andExpect(status().is4xxClientError());
    }
}
