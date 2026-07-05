package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.InternalAuthFilter;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.PresignService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/**
 * End-to-end coverage for the Run Start Dialog attachment seam.
 *
 * <p>This test was written after a regression where the upload step wrote attachments to
 * {@code {orgSlug}/staging/{stagingId}/...} and the run-start step persisted that path
 * verbatim into {@code workflow_run.input_artifact_refs}. The agent's per-execution
 * presign endpoint then rejected the staging path with 403 because its scope guard only
 * allows {@code {orgSlug}/runs/{runId}/...}. Each unit test on either side of the seam
 * passed; nothing exercised the contract between them.
 *
 * <p>The test walks the full happy path: upload → start run → confirm the persisted
 * refs were rewritten into the run-scoped prefix → confirm the agent can presign that
 * rewritten path → confirm a stale staging path is still forbidden.
 */
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class RunStartAttachmentE2ETest extends BaseTest {

    private static final String JOB_SECRET = "test-agent-job-secret";

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

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private S3Client s3Client;

    @MockitoBean
    private PresignService presignService;

    private UUID templateId;

    @BeforeEach
    void setUp() {
        WorkflowStub stub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(any(String.class), any(WorkflowOptions.class)))
                .thenReturn(stub);

        // Minimal graph: one entrypoint AI node. Enough for startRun's validation to pass.
        GraphTemplate template = new GraphTemplate();
        template.setName("Attachment Seam Test Template");
        template.setGraphId("attach-seam-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);
        templateId = template.getId();

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("attach-seam-node");
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
        templateNode.setLabel("Attach Seam Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNodeRepo.save(templateNode);
    }

    @Test
    void uploadedAttachment_isMovedIntoRunScope_andPresignableByAgent() throws Exception {
        // 1. Upload a file via the Run Start Dialog endpoint.
        MockMultipartFile file = new MockMultipartFile("files", "design.png", "image/png", "fake-png-bytes".getBytes());

        MvcResult uploadResult = mockMvc.perform(
                        multipart("/api/v1/attachments/temp").file(file))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode uploadJson = objectMapper.readTree(uploadResult.getResponse().getContentAsString());
        String stagingRefs = uploadJson.get("stagingRefs").asText();
        JsonNode refs = objectMapper.readTree(stagingRefs);
        String stagingKey = refs.get("design.png").asText();
        assertThat(stagingKey).startsWith("system/staging/").endsWith("/design.png");

        // 2. Start a run with those staged refs.
        Map<String, Object> body = Map.of(
                "graphTemplateId", templateId,
                "inputs", Map.of(),
                "inputAttachmentRefs", stagingRefs);

        MvcResult runResult = mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode runJson = objectMapper.readTree(runResult.getResponse().getContentAsString());
        UUID runId = UUID.fromString(runJson.get("id").asText());

        // 3. The persisted refs must be rewritten into the run-scoped prefix.
        String persisted = runRepo.findById(runId).orElseThrow().getInputArtifactRefs();
        JsonNode persistedJson = objectMapper.readTree(persisted);
        String runScopedKey = persistedJson.get("design.png").asText();
        assertThat(runScopedKey).isEqualTo("system/runs/" + runId + "/inputs/design.png");

        // 4. The object store saw a copy from staging into the run prefix.
        ArgumentCaptor<CopyObjectRequest> copyCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(copyCaptor.capture());
        assertThat(copyCaptor.getValue().destinationKey()).isEqualTo(runScopedKey);

        // 5. ...followed by a hard-delete of the staging copy.
        ArgumentCaptor<DeleteObjectRequest> rmCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(rmCaptor.capture());
        assertThat(rmCaptor.getValue().key()).isEqualTo(stagingKey);

        // 6. Simulate the orchestrator creating a NodeExecution row so we can act as the agent.
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(
                templateNodeRepo.findByGraphTemplateId(templateId).get(0).getId());
        exec.setGraphVersion(1);
        exec.setJobSecretHash(InternalAuthFilter.sha256Hex(JOB_SECRET));
        exec = execRepo.save(exec);

        // 7. Agent presigns the rewritten path — must succeed.
        when(presignService.generatePresignedUrl(runScopedKey, "GET")).thenReturn("https://minio:9000/signed");
        mockMvc.perform(get("/internal/runs/" + runId + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", runScopedKey)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://minio:9000/signed"));

        // 8. The original staging path must still be rejected — defense in depth, even if a
        //    stale ref ever reached the agent it would not be able to fetch it.
        mockMvc.perform(get("/internal/runs/" + runId + "/node-executions/" + exec.getId() + "/presign")
                        .param("path", stagingKey)
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied: path outside allowed scope"));
    }
}
