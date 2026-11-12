package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.InternalAuthFilter;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Every write that records an execution's artifact paths refuses one outside its run's own
 * storage tree, before anything is read or stored, from each of the four callers that can reach
 * it — the agent's own {@code JOB_SECRET}, the orchestrator secret and a Worker credential on the
 * status route, plus the orchestrator secret on review history.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce",
            "worker.registration.token=test-fleet",
            "artifact.enforcement.mode=enforce"
        })
class ArtifactRefContainmentIntegrationTest extends BaseTest {

    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";
    private static final String JOB_SECRET = "test-containment-job-secret";

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

    @MockitoBean
    private S3Client s3Client;

    private WorkflowRun run;
    private NodeExecution exec;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Containment Test Template");
        template.setGraphId("containment-test-template-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("containment-test-node");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        // A required output file, so a completion that skips containment would otherwise be
        // caught (and wrongly reported) by the missing-output check instead.
        nodeDef.setOutputSpec("{\"files\":[{\"name\":\"out.md\",\"required\":true}]}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("Containment Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run = runRepo.save(run);

        exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec.setJobSecretHash(InternalAuthFilter.sha256Hex(JOB_SECRET));
        exec = execRepo.save(exec);
    }

    private String outsideTreeRefs() {
        return "{\"output\":\"system/runs/" + UUID.randomUUID() + "/out/\"}";
    }

    private String inTreeRefs() {
        return "{\"output\":\"system/runs/" + run.getId() + "/" + exec.getId() + "/out/\"}";
    }

    // ── status write, each caller ───────────────────────────────────────────

    @Test
    void statusWrite_agentsOwnSecret_pathOutsideRunsTree_isBadRequest_rowUnchanged() throws Exception {
        Map<String, Object> body = Map.of("status", "completed", "artifactRefs", outsideTreeRefs());

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isBadRequest());

        NodeExecution row = execRepo.findById(exec.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NodeExecutionStatus.pending);
        assertThat(row.getArtifactRefs()).isEqualTo("{}");
        verifyNoInteractions(s3Client);
    }

    @Test
    void statusWrite_orchestratorSecret_pathOutsideRunsTree_isBadRequest_rowUnchanged() throws Exception {
        Map<String, Object> body = Map.of("status", "completed", "artifactRefs", outsideTreeRefs());

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isBadRequest());

        NodeExecution row = execRepo.findById(exec.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NodeExecutionStatus.pending);
        assertThat(row.getArtifactRefs()).isEqualTo("{}");
    }

    @Test
    void statusWrite_workerCredential_pathOutsideRunsTree_isBadRequest_rowUnchanged() throws Exception {
        Map<String, Object> body = Map.of("status", "completed", "artifactRefs", outsideTreeRefs());

        mockMvc.perform(put("/worker/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isBadRequest());

        NodeExecution row = execRepo.findById(exec.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NodeExecutionStatus.pending);
        assertThat(row.getArtifactRefs()).isEqualTo("{}");
    }

    // ── required outputs + escalate decision ────────────────────────────────

    @Test
    void completion_requiredOutputsAndEscalateDecision_outsideRunsTree_getsContainmentNotOutputOrEscalationError()
            throws Exception {
        exec.setDecision("escalate");
        execRepo.save(exec);

        Map<String, Object> body = Map.of("status", "completed", "artifactRefs", outsideTreeRefs());

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("escalation.md"))));

        verifyNoInteractions(s3Client);
        NodeExecution row = execRepo.findById(exec.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NodeExecutionStatus.pending);
    }

    // ── review history ───────────────────────────────────────────────────────

    @Test
    void reviewHistory_pathOutsideRunsTree_isBadRequest_rowUnchanged() throws Exception {
        Map<String, Object> body = Map.of(
                "loopGroup",
                "loop-1",
                "iteration",
                1,
                "reviewerType",
                "human",
                "artifactRefs",
                outsideTreeRefs(),
                "nodeExecutionId",
                exec.getId().toString());

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/review-history")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isBadRequest());

        NodeExecution row = execRepo.findById(exec.getId()).orElseThrow();
        assertThat(row.getLoopGroup()).isNull();
        assertThat(row.getArtifactRefs()).isEqualTo("{}");
    }

    // ── follow-up after a refusal ────────────────────────────────────────────

    @Test
    void followUpFailedWriteWithNoArtifactRefs_succeedsAfterAnEarlierRefusal() throws Exception {
        Map<String, Object> refused = Map.of("status", "completed", "artifactRefs", outsideTreeRefs());
        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(refused))
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isBadRequest());

        Map<String, Object> failed = Map.of("status", "failed", "errorMessage", "timed out");
        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(failed))
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"));

        NodeExecution row = execRepo.findById(exec.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(NodeExecutionStatus.failed);
    }

    // ── accepted writes ───────────────────────────────────────────────────────

    @Test
    void statusWrite_pathInsideRunsTree_isAccepted() throws Exception {
        Map<String, Object> body = Map.of("status", "running", "artifactRefs", inTreeRefs());

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk());

        assertThat(execRepo.findById(exec.getId()).orElseThrow().getArtifactRefs())
                .isEqualTo(inTreeRefs());
    }

    @Test
    void statusWrite_serverBuiltGateAttachmentAndGuidancePaths_areAccepted() throws Exception {
        String refs = "{\"spec.pdf\":\"system/runs/" + run.getId() + "/gate-attachments/" + exec.getId()
                + "/spec.pdf\",\"human_guidance.md\":\"system/runs/" + run.getId() + "/gate-attachments/"
                + exec.getId() + "/human_guidance.md\"}";
        Map<String, Object> body = Map.of("status", "running", "artifactRefs", refs);

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk());

        assertThat(execRepo.findById(exec.getId()).orElseThrow().getArtifactRefs())
                .isEqualTo(refs);
    }

    @Test
    void statusWrite_emptyArtifactRefsObject_isUnaffected() throws Exception {
        Map<String, Object> body = Map.of("status", "running", "artifactRefs", "{}");

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk());
    }

    @Test
    void statusWrite_absentArtifactRefs_isUnaffected() throws Exception {
        Map<String, Object> body = Map.of("status", "running");

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk());
    }
}
