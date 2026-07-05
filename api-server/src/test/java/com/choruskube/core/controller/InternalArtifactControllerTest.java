package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.InternalAuthFilter;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.*;
import com.choruskube.core.service.PresignService;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class InternalArtifactControllerTest extends BaseTest {

    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";
    private static final String JOB_SECRET = "test-agent-job-secret";

    @Autowired
    private MockMvc mockMvc;

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
    private PresignService presignService;

    private WorkflowRun run;
    private NodeExecution exec;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Presign Test Template");
        template.setGraphId("presign-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("presign-test-node");
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
        templateNode.setLabel("Presign Test Node");
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

    @Test
    void presign_withValidAgentToken_returnsUrl() throws Exception {
        String path = "system/runs/" + run.getId() + "/some-node/out/file.txt";
        when(presignService.generatePresignedUrl(path, "GET")).thenReturn("https://minio:9000/signed-url");

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://minio:9000/signed-url"));
    }

    @Test
    void presign_withOrchestratorToken_returnsUrl() throws Exception {
        String path = "system/runs/" + run.getId() + "/some-node/out/file.txt";
        when(presignService.generatePresignedUrl(path, "PUT")).thenReturn("https://minio:9000/put-url");

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "PUT")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://minio:9000/put-url"));
    }

    @Test
    void presign_crossRunPath_isForbidden() throws Exception {
        String crossRunPath = "system/runs/" + UUID.randomUUID() + "/node/out/file.txt";

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", crossRunPath)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());
    }

    @Test
    void presign_unprefixedLegacyPath_isForbidden() throws Exception {
        // Legacy unprefixed paths ({@code runs/{runId}/...}) are no longer accepted —
        // every org has a slug, so the only valid shape is {@code {orgSlug}/runs/{runId}/...}.
        String path = "runs/" + run.getId() + "/some-node/out/file.txt";

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());
    }

    @Test
    void presign_stagingPath_isForbidden() throws Exception {
        // Staging paths must be moved into the run prefix at run-create time
        // (RunService.startRun → UploadService.copyStagingToRun); they must never
        // be presignable directly by an agent.
        String path = "system/staging/" + UUID.randomUUID() + "/file.png";

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());
    }

    @Test
    void presign_runInputsPath_succeeds() throws Exception {
        // Input attachments moved by RunService land at {orgSlug}/runs/{runId}/inputs/...
        // and must be presignable by the agent.
        String path = "system/runs/" + run.getId() + "/inputs/screenshot.png";
        when(presignService.generatePresignedUrl(path, "GET")).thenReturn("https://minio:9000/inputs-url");

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://minio:9000/inputs-url"));
    }

    @Test
    void presign_missingPath_isBadRequest() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presign_invalidMethod_isBadRequest() throws Exception {
        String path = "system/runs/" + run.getId() + "/file.txt";

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "DELETE")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presign_noAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", "system/runs/" + run.getId() + "/file.txt")
                        .param("method", "GET"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void presign_pathTraversal_isForbidden() throws Exception {
        String maliciousPath = "system/runs/" + run.getId() + "/../../etc/passwd";

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", maliciousPath)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());
    }

    @Test
    void presign_crossOrgAgent_isForbidden() throws Exception {
        WorkflowRun runB = new WorkflowRun();
        runB.setGraphTemplateId(run.getGraphTemplateId());
        runB = runRepo.save(runB);

        // Agent from system org tries to presign a path for org-b's run
        String path = "org-b/runs/" + runB.getId() + "/some-node/out/file.txt";

        mockMvc.perform(get("/internal/runs/" + runB.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied: path outside allowed scope"));
    }

    @Test
    void presign_orgPrefixMismatch_isForbidden() throws Exception {
        String path = "wrong-org/runs/" + run.getId() + "/some-node/out/file.txt";

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", path)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());
    }
}
