package com.choruskube.core.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.WorkflowRunStatus;
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
    private WorkflowRun foreignRun;
    private NodeExecution exec;
    private NodeExecution otherExec;

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

        // A run `exec` does NOT belong to — proves the filter compares runs, not just the secret.
        foreignRun = new WorkflowRun();
        foreignRun.setGraphTemplateId(template.getId());
        foreignRun = runRepo.save(foreignRun);

        // Create a node execution with a known job secret hash
        String jobSecret = "test-agent-job-secret";
        exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec.setJobSecretHash(InternalAuthFilter.sha256Hex(jobSecret));
        exec = execRepo.save(exec);

        // A second, distinct node execution (same run) with its own job secret — used to prove a
        // *real, valid* secret for one execution is rejected against a *different* real
        // execution's id on the same route shape, not merely that an arbitrary/nonexistent id 401s.
        otherExec = new NodeExecution();
        otherExec.setWorkflowRunId(run.getId());
        otherExec.setTemplateNodeId(templateNode.getId());
        otherExec.setGraphVersion(1);
        // Distinct iteration: (workflow_run_id, template_node_id, iteration) is unique, and `exec`
        // above already occupies iteration 1 for this run/templateNode pair.
        otherExec.setIteration(2);
        otherExec.setJobSecretHash(InternalAuthFilter.sha256Hex("test-other-agent-job-secret"));
        otherExec = execRepo.save(otherExec);
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
    void agentToken_deniesGraphEndpointForOtherExecution() throws Exception {
        // A valid job secret for `exec` must not grant access to the same route shape
        // (`.../node-executions/{nodeExecId}/graph`) scoped to a *different*, real execution's id.
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + otherExec.getId() + "/graph")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentToken_ownExecutionUnderForeignRun_isUnauthorized() throws Exception {
        // The run named at the fixed position must match the authenticated execution's own run.
        mockMvc.perform(get("/internal/runs/" + foreignRun.getId() + "/node-executions/" + exec.getId() + "/decision")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid or insufficient Bearer token"));
    }

    @Test
    void agentToken_executionWindowShiftedByExtraLeadingSegment_isUnauthorized() throws Exception {
        // The fixed window is read at a fixed position, not found anywhere in the path.
        mockMvc.perform(get("/internal/x/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/decision")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentToken_executionWindowShiftedByInsertedSegment_isUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/x/node-executions/" + exec.getId())
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentToken_nonUuidRunSegment_isUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/runs/not-a-uuid/node-executions/" + exec.getId() + "/decision")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentToken_nonUuidExecutionSegment_isUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/not-a-uuid/decision")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void escapedInternalPath_noToken_isUnauthorizedByTheFilter() throws Exception {
        // Deciding from the decoded path means an escaped prefix is filtered exactly like the
        // plain one, rather than falling through unauthenticated to the guard underneath.
        mockMvc.perform(get(java.net.URI.create("/%69nternal/runs/" + run.getId() + "/graph-runtime")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid Authorization header"));
    }

    @Test
    void escapedInternalPath_withAgentOwnSecret_isRefusedByTheGuardNotTheFilter() throws Exception {
        // Proves the agent was authenticated on the decoded path: the filter let its own pair
        // through, and only the guard — reading the raw, still-escaped URI — refused it.
        mockMvc.perform(get(java.net.URI.create(
                                "/%69nternal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/decision"))
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(RunScopeInterceptor.ESCAPED_PATH_MESSAGE));
    }

    @Test
    void escapedPublicApiPath_withAgentToken_isForbiddenLikeThePlainOne() throws Exception {
        mockMvc.perform(get(java.net.URI.create("/%61pi/v1/runs"))
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Agent pods must use /internal/ endpoints"));
    }

    @Test
    void orchestratorToken_onForeignPair_isNotRefusedByTheFilter() throws Exception {
        // The orchestrator tier is never checked against the run by the filter — only the guard
        // underneath refuses this pair, with its own 404.
        mockMvc.perform(get("/internal/runs/" + foreignRun.getId() + "/node-executions/" + exec.getId() + "/decision")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Node execution not found: " + exec.getId()));
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
    void agentToken_onRemovedGitHubTokenPath_isNotFound() throws Exception {
        // The filter still authorizes this path shape by job-secret; a 404 here proves no
        // controller method serves a token at this address anymore.
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/github-token")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isNotFound());
    }

    // ── removed legacy branch cleanup: gone, and still behind the filter ──────────

    @Test
    void orchestratorToken_onRemovedCleanupBranchesPath_isNotFound() throws Exception {
        // The orchestrator's secret passes the filter; a 404 here proves no controller method
        // serves the API-side cleanup at this address anymore.
        mockMvc.perform(post("/internal/runs/" + run.getId() + "/cleanup-branches")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNotFound());
    }

    @Test
    void noAuth_onRemovedCleanupBranchesPath_isUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/runs/" + run.getId() + "/cleanup-branches"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid Authorization header"));
    }

    // ── branch cleanup plan: orchestrator-only, by construction ───────────────────
    //
    // /internal/runs/{runId}/branch-cleanup-plan carries no node-executions/{execId} segment at the
    // fixed window, so the filter's agent tier never matches it and an agent's JOB_SECRET can never
    // authorize it — only the orchestrator's shared secret can.

    @Test
    void orchestratorToken_canAccessBranchCleanupPlanEndpoint() throws Exception {
        run.setStatus(WorkflowRunStatus.completed);
        run = runRepo.save(run);

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/branch-cleanup-plan")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repos").isArray());
    }

    @Test
    void agentToken_deniesBranchCleanupPlanEndpoint() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/branch-cleanup-plan")
                        .header("Authorization", "Bearer test-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noAuth_deniesBranchCleanupPlanEndpoint() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/branch-cleanup-plan"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid Authorization header"));
    }

    // ── Task issue close record: orchestrator-only, by construction ───────────────
    //
    // /internal/tasks/{taskId}/github-issue/closed has no node-executions/{execId} segment, so a
    // job secret — even one valid for a real execution — can never authorize it.

    @Test
    void orchestratorToken_passesTaskIssueClosedEndpoint() throws Exception {
        // No linkage for this Task, so the controller answers 404 — proof the filter let it through.
        mockMvc.perform(post("/internal/tasks/" + java.util.UUID.randomUUID() + "/github-issue/closed")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueNumber\":7}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentToken_deniesTaskIssueClosedEndpoint() throws Exception {
        mockMvc.perform(post("/internal/tasks/" + java.util.UUID.randomUUID() + "/github-issue/closed")
                        .header("Authorization", "Bearer test-agent-job-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueNumber\":7}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noAuth_deniesTaskIssueClosedEndpoint() throws Exception {
        mockMvc.perform(post("/internal/tasks/" + java.util.UUID.randomUUID() + "/github-issue/closed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issueNumber\":7}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing or invalid Authorization header"));
    }
}
