package com.choruskube.core.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests for InternalAuthFilter in enforce mode.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            // SHA-256 hash of "test-orchestrator-secret"
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
public class InternalAuthFilterTest extends BaseTest {

    /** The token whose SHA-256 hash is the value in the @TestPropertySource above. */
    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private WorkflowRun run;
    private NodeExecution exec;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Auth Test Template");
        template.setGraphId("auth-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("auth-test-node");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("Auth Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run = runRepo.save(run);

        // Create a node execution with a known job secret hash
        String jobSecret = "test-agent-job-secret";
        exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec.setJobSecretHash(InternalAuthFilter.sha256Hex(jobSecret));
        exec = execRepo.save(exec);
    }

    @Test
    void requestWithoutAuth_isRejected() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/graph-runtime"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid Authorization header"));
    }

    @Test
    void requestWithInvalidToken_isRejected() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/graph-runtime")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid or insufficient Bearer token"));
    }

    @Test
    void requestWithOrchestratorToken_isAllowed() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/graph-runtime")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk());
    }

    @Test
    void agentToken_allowsOwnExecutionEndpoint() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId())
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void agentToken_deniesOtherEndpoint() throws Exception {
        // Agent token should not grant access to non-node-execution endpoints
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/graph-runtime")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpoints_notAffected() throws Exception {
        // Public API should not require auth (filter only applies to /internal/**)
        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isOk());
    }

    @Test
    void orchestratorToken_canAccessAnyInternalEndpoint() throws Exception {
        // Orchestrator can access node-execution endpoint
        Map<String, Object> body = Map.of("status", "running");
        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void agentToken_allowsGitHubTokenEndpoint() throws Exception {
        // Agent token should grant access to the scoped github-token endpoint: the request must
        // reach the resolver rather than being rejected (401/403). In the single-tenant test env no
        // GITHUB_PAT or GitHub App is configured, so the core env resolver throws IllegalStateException
        // (-> 500). The point is that auth passed — contrast noAuth_deniesGitHubTokenEndpoint (401).
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/github-token")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void noAuth_deniesGitHubTokenEndpoint() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/github-token"))
                .andExpect(status().isUnauthorized());
    }
}
