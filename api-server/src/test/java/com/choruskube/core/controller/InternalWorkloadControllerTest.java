package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.executor.*;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.*;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for the full orchestrator-to-API-server workload flow.
 *
 * <p>Exercises the real HTTP path: MockMvc → InternalWorkloadController →
 * WorkloadService → (mock) WorkloadExecutor, with a real PostgreSQL database
 * via TestContainers. The executor is mocked because we cannot run Docker/K8s
 * in CI, but everything else (auth filter, controller, service, JPA) is real.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            // SHA-256 hash of "test-orchestrator-secret"
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
public class InternalWorkloadControllerTest extends BaseTest {

    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    /** Replaces the noop executor bean with a controllable mock. */
    @MockitoBean
    private WorkloadExecutor workloadExecutor;

    private WorkflowRun run;
    private NodeExecution nodeExec;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Workload Integration Test");
        template.setGraphId("workload-integ");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("test-ai-node");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test prompt");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("test_node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        GitRepo defaultRepo = new GitRepo();
        String defaultRepoUrl = "https://github.com/test/default-repo-" + UUID.randomUUID();
        defaultRepo.setUrl(defaultRepoUrl);
        defaultRepo.setName(RepoNameUtil.deriveOwnerRepoName(defaultRepoUrl));
        defaultRepo.setDefaultBranch("main");
        defaultRepo = gitRepoRepo.save(defaultRepo);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{\"git_repo_id\":\"" + defaultRepo.getId() + "\"}");
        run = runRepo.save(run);

        nodeExec = new NodeExecution();
        nodeExec.setWorkflowRunId(run.getId());
        nodeExec.setTemplateNodeId(templateNode.getId());
        nodeExec.setGraphVersion(1);
        nodeExec.setStatus(NodeExecutionStatus.pending);
        nodeExec = execRepo.save(nodeExec);
    }

    // --- CreateWorkload: full flow ---

    @Test
    void createWorkload_fullFlow_updatesDbAtomically() throws Exception {
        when(workloadExecutor.execute(any())).thenReturn(new ExecutionResult("agent-abc12345", "hash-of-job-secret"));

        // Slim request: only templateNodeId + configJson. Image/namespace/etc resolved from snapshot.
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "templateNodeId",
                nodeExec.getTemplateNodeId().toString(),
                "configJson",
                Map.of("callback_url", "http://orchestrator:9090/callback")));

        MvcResult result = mockMvc.perform(
                        post("/internal/workloads/{runId}/{nodeExecId}", run.getId(), nodeExec.getId())
                                .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        // Verify response body
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("executionHandle").asText()).isEqualTo("agent-abc12345");
        assertThat(response.get("jobSecretHash").asText()).isEqualTo("hash-of-job-secret");

        // Verify executor was called with correct params (image resolved from NodeDefinition)
        ArgumentCaptor<ExecutionParams> captor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(workloadExecutor).execute(captor.capture());
        ExecutionParams params = captor.getValue();
        assertThat(params.nodeExecutionId()).isEqualTo(nodeExec.getId());
        assertThat(params.runId()).isEqualTo(run.getId());
        assertThat(params.image()).isEqualTo("test:latest"); // from NodeDefinition setup
        assertThat(params.configJson()).containsEntry("callback_url", "http://orchestrator:9090/callback");

        // Verify the DB was atomically updated (status, podName, jobSecretHash)
        NodeExecution updated = execRepo.findById(nodeExec.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NodeExecutionStatus.running);
        assertThat(updated.getPodName()).isEqualTo("agent-abc12345");
        assertThat(updated.getJobSecretHash()).isEqualTo("hash-of-job-secret");
        assertThat(updated.getStartedAt()).isNotNull();
    }

    @Test
    void createWorkload_resolvesDockerAndSecretsFromGitRepo() throws Exception {
        // Set up a GitRepo with docker enabled and secrets
        GitRepo gitRepo = new GitRepo();
        String gitRepoUrl = "https://github.com/test/repo-" + UUID.randomUUID();
        gitRepo.setUrl(gitRepoUrl);
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName(gitRepoUrl));
        gitRepo.setDefaultBranch("main");
        gitRepo.setEnableDocker(true);
        gitRepo.setSecrets("""
                [{"name":"anthropic-api-key","mountType":"env"}]
                """);
        gitRepo = gitRepoRepo.save(gitRepo);

        // Update the run inputs to reference the git repo
        run.setInputs(objectMapper.writeValueAsString(
                Map.of("git_repo_id", gitRepo.getId().toString())));
        run = runRepo.save(run);

        when(workloadExecutor.execute(any())).thenReturn(new ExecutionResult("agent-xyz99999", "hash999"));

        Map<String, Object> body = Map.of(
                "templateNodeId", nodeExec.getTemplateNodeId().toString(),
                "configJson", Map.of("prompt", "do the thing"));

        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", run.getId(), nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.executionHandle").value("agent-xyz99999"));

        ArgumentCaptor<ExecutionParams> captor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(workloadExecutor).execute(captor.capture());
        ExecutionParams params = captor.getValue();

        assertThat(params.enableDocker()).isTrue();
        assertThat(params.nodeCredentials()).hasSize(1);
        assertThat(params.nodeCredentials().getFirst().source()).isEqualTo("anthropic-api-key");
        // Identity uses system defaults
        assertThat(params.identity().name()).isEqualTo("choruskube-agent");
    }

    @Test
    void createWorkload_missingExecution_returns404() throws Exception {
        UUID fakeExecId = UUID.randomUUID();

        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", run.getId(), fakeExecId)
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateNodeId", nodeExec.getTemplateNodeId().toString(), "configJson", Map.of()))))
                .andExpect(status().isNotFound());

        verify(workloadExecutor, never()).execute(any());
    }

    @Test
    void createWorkload_executorThrows_returns500() throws Exception {
        when(workloadExecutor.execute(any())).thenThrow(new RuntimeException("K8s API unreachable"));

        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", run.getId(), nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateNodeId", nodeExec.getTemplateNodeId().toString(), "configJson", Map.of()))))
                .andExpect(status().is5xxServerError());

        // DB should NOT have been updated (transaction rolled back)
        NodeExecution unchanged = execRepo.findById(nodeExec.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(NodeExecutionStatus.pending);
        assertThat(unchanged.getPodName()).isNull();
    }

    // --- CleanupWorkload ---

    @Test
    void cleanupWorkload_delegatesAndReturns204() throws Exception {
        mockMvc.perform(delete("/internal/workloads/{executionId}", nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNoContent());

        verify(workloadExecutor).cleanup(nodeExec.getId());
    }

    @Test
    void testDeleteWorkloadIdempotent() throws Exception {
        // WorkloadService.cleanupWorkload() is a no-op mock (doNothing by default).
        // Calling DELETE /internal/workloads/{id} twice must return 204 both times.
        UUID execId = nodeExec.getId();

        mockMvc.perform(delete("/internal/workloads/{execId}", execId)
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/internal/workloads/{execId}", execId)
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNoContent());
    }

    // --- GetWorkloadLogs ---

    @Test
    void getWorkloadLogs_returnsLogsJson() throws Exception {
        when(workloadExecutor.getLogs(nodeExec.getId(), 100)).thenReturn("line1\nline2\nline3");

        MvcResult result = mockMvc.perform(get("/internal/workloads/{executionId}/logs", nodeExec.getId())
                        .param("tailLines", "100")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.get("logs").asText()).isEqualTo("line1\nline2\nline3");
    }

    @Test
    void getWorkloadLogs_defaultTailLines() throws Exception {
        when(workloadExecutor.getLogs(nodeExec.getId(), 50)).thenReturn("default");

        mockMvc.perform(get("/internal/workloads/{executionId}/logs", nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logs").value("default"));

        verify(workloadExecutor).getLogs(nodeExec.getId(), 50);
    }

    // --- TerminateWorkload ---

    @Test
    void terminateWorkload_delegatesAndReturns204() throws Exception {
        mockMvc.perform(post("/internal/workloads/{executionId}/terminate", nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNoContent());

        verify(workloadExecutor).terminate(nodeExec.getId());
    }

    // --- ListWorkloads ---

    @Test
    void listWorkloads_returnsExecutorResults() throws Exception {
        var info = new ExecutionInfo(nodeExec.getId(), run.getId(), "agent-abc12345");
        when(workloadExecutor.listExecutions()).thenReturn(List.of(info));

        MvcResult result = mockMvc.perform(
                        get("/internal/workloads").header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.isArray()).isTrue();
        assertThat(response).hasSize(1);
        assertThat(response.get(0).get("nodeExecutionId").asText())
                .isEqualTo(nodeExec.getId().toString());
        assertThat(response.get(0).get("executionHandle").asText()).isEqualTo("agent-abc12345");
    }

    // --- HealthCheck ---

    @Test
    void healthCheck_returnsOk() throws Exception {
        mockMvc.perform(get("/internal/workloads/health").header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk());

        verify(workloadExecutor).healthCheck();
    }

    @Test
    void healthCheck_executorUnhealthy_returns500() throws Exception {
        doThrow(new RuntimeException("connection refused"))
                .when(workloadExecutor)
                .healthCheck();

        mockMvc.perform(get("/internal/workloads/health").header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().is5xxServerError());
    }

    // --- Auth enforcement ---

    @Test
    void createWorkload_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", run.getId(), nodeExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateNodeId", nodeExec.getTemplateNodeId().toString(), "configJson", Map.of()))))
                .andExpect(status().isUnauthorized());

        verify(workloadExecutor, never()).execute(any());
    }

    @Test
    void createWorkload_withInvalidToken_returns401() throws Exception {
        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", run.getId(), nodeExec.getId())
                        .header("Authorization", "Bearer wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "templateNodeId", nodeExec.getTemplateNodeId().toString(), "configJson", Map.of()))))
                .andExpect(status().isUnauthorized());

        verify(workloadExecutor, never()).execute(any());
    }

    @Test
    void listWorkloads_withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/internal/workloads")).andExpect(status().isUnauthorized());

        verify(workloadExecutor, never()).listExecutions();
    }
}
