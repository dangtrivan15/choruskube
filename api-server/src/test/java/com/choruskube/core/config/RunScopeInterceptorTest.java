package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.ExecutionLogRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.PresignService;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * {@link RunScopeInterceptor} end to end through MockMvc: a real pair passes, a foreign pair or an
 * escaped path is refused before any handler code runs, and the refusal applies uniformly to the
 * agent, the orchestrator and a Worker credential alike.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            // SHA-256 hash of "test-orchestrator-secret"
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce",
            "worker.registration.token=test-fleet-token"
        })
class RunScopeInterceptorTest extends BaseTest {

    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";
    private static final String JOB_SECRET_X = "run-scope-test-job-secret-x";

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

    @Autowired
    private ExecutionLogRepository executionLogRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private PresignService presignService;

    private WorkflowRun runA;
    private WorkflowRun runB;
    private NodeExecution execX;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Run Scope Test Template");
        template.setGraphId("run-scope-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("run-scope-test-node");
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
        templateNode.setLabel("Run Scope Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        runA = new WorkflowRun();
        runA.setGraphTemplateId(template.getId());
        runA = runRepo.save(runA);

        runB = new WorkflowRun();
        runB.setGraphTemplateId(template.getId());
        runB = runRepo.save(runB);

        execX = new NodeExecution();
        execX.setWorkflowRunId(runA.getId());
        execX.setTemplateNodeId(templateNode.getId());
        execX.setGraphVersion(1);
        execX.setJobSecretHash(InternalAuthFilter.sha256Hex(JOB_SECRET_X));
        execX = execRepo.save(execX);
    }

    @Test
    void ownPair_passesThrough() throws Exception {
        mockMvc.perform(get("/internal/runs/" + runA.getId() + "/node-executions/" + execX.getId() + "/decision")
                        .header("Authorization", "Bearer " + JOB_SECRET_X))
                .andExpect(status().isOk());
    }

    @Test
    void ownExecutionUnderForeignRun_isNotFound() throws Exception {
        mockMvc.perform(get("/internal/runs/" + runB.getId() + "/node-executions/" + execX.getId() + "/decision")
                        .header("Authorization", "Bearer " + JOB_SECRET_X))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Node execution not found: " + execX.getId()));
    }

    @Test
    void foreignPairWithOrchestratorSecret_isNotFound() throws Exception {
        mockMvc.perform(get("/internal/runs/" + runB.getId() + "/node-executions/" + execX.getId() + "/decision")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Node execution not found: " + execX.getId()));
    }

    @Test
    void unknownExecutionUnderRunA_withOrchestratorSecret_isNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/internal/runs/" + runA.getId() + "/node-executions/" + unknown + "/decision")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Node execution not found: " + unknown));
    }

    @Test
    void nonUuidRunId_withOrchestratorSecret_isBadRequest() throws Exception {
        mockMvc.perform(get("/internal/runs/not-a-uuid/node-executions/" + execX.getId() + "/decision")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid run or node execution id"));
    }

    @Test
    void guardRunsBeforeValidation_foreignPairWithInvalidBody_is404NotBadRequest() throws Exception {
        mockMvc.perform(post("/internal/runs/" + runB.getId() + "/node-executions/" + execX.getId() + "/pull-requests")
                        .header("Authorization", "Bearer " + JOB_SECRET_X)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void foreignStatusUpdate_isNotFound_andWritesNothing() throws Exception {
        mockMvc.perform(put("/internal/runs/" + runB.getId() + "/node-executions/" + execX.getId() + "/status")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"completed\"}"))
                .andExpect(status().isNotFound());

        NodeExecution unchanged = execRepo.findById(execX.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(NodeExecutionStatus.pending);
        assertThat(unchanged.getJobSecretHash()).isEqualTo(InternalAuthFilter.sha256Hex(JOB_SECRET_X));
        assertThat(unchanged.getArtifactRefs()).isEqualTo("{}");
    }

    @Test
    void foreignLogWrite_isNotFound_andWritesNoRow() throws Exception {
        mockMvc.perform(post("/internal/runs/" + runB.getId() + "/node-executions/" + execX.getId() + "/logs")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"info\",\"message\":\"hi\"}"))
                .andExpect(status().isNotFound());

        assertThat(executionLogRepo.findByNodeExecutionIdOrderByTimestampAsc(execX.getId()))
                .isEmpty();
    }

    @Test
    void foreignPresign_isNotFound() throws Exception {
        mockMvc.perform(get("/internal/runs/" + runB.getId() + "/node-executions/" + execX.getId() + "/presign")
                        .param("path", "system/runs/" + runB.getId() + "/file.txt")
                        .param("method", "GET")
                        .header("Authorization", "Bearer " + JOB_SECRET_X))
                .andExpect(status().isNotFound());
    }

    @Test
    void gateFinalizeUnderRenamedVariable_foreignPair_isNotFound() throws Exception {
        mockMvc.perform(post("/internal/runs/" + runB.getId() + "/node-executions/" + execX.getId()
                                + "/gate-finalize/held")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isNotFound());
    }

    @Test
    void escapedInternalPath_noToken_isBadRequest() throws Exception {
        mockMvc.perform(get(URI.create("/%69nternal/runs/" + runA.getId() + "/graph-runtime")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(RunScopeInterceptor.ESCAPED_PATH_MESSAGE));
    }

    @Test
    void escapedInternalPath_withOrchestratorSecret_isStillBadRequest() throws Exception {
        mockMvc.perform(get(URI.create("/%69nternal/runs/" + runA.getId() + "/graph-runtime"))
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(RunScopeInterceptor.ESCAPED_PATH_MESSAGE));
    }

    @Test
    void unescapedInternalPath_withOrchestratorSecret_controlCase_isOk() throws Exception {
        mockMvc.perform(get("/internal/runs/" + runA.getId() + "/graph-runtime")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk());
    }

    @Test
    void escapeInInnerSegment_isBadRequest() throws Exception {
        mockMvc.perform(get(URI.create(
                                "/internal/r%75ns/" + runA.getId() + "/node-executions/" + execX.getId() + "/decision"))
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(RunScopeInterceptor.ESCAPED_PATH_MESSAGE));
    }

    @Test
    void escapeInQueryString_isNotRefusedByTheGuard() throws Exception {
        String decodedPath = "system/runs/" + runA.getId() + "/file.txt";
        when(presignService.generatePresignedUrl(decodedPath, "GET")).thenReturn("https://minio:9000/signed");

        URI uri = URI.create("/internal/runs/" + runA.getId() + "/node-executions/" + execX.getId()
                + "/presign?path=system/runs/" + runA.getId() + "%2Ffile.txt&method=GET");

        var result = mockMvc.perform(get(uri).header("Authorization", "Bearer " + JOB_SECRET_X))
                .andReturn();
        boolean refusedByGuard = result.getResponse().getStatus() == 400
                && RunScopeInterceptor.ESCAPED_PATH_MESSAGE.equals(
                        result.getResponse().getContentAsString());
        assertThat(refusedByGuard).isFalse();
    }

    @Test
    void workerRoute_foreignPair_isNotFound_beforeCredentialCheck() throws Exception {
        mockMvc.perform(put("/worker/runs/" + runB.getId() + "/node-executions/" + execX.getId() + "/status")
                        .header("Authorization", "Bearer anything")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"completed\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void workerRoute_ownPair_passesToTheController_andIsRejectedByTheAuthorizer() throws Exception {
        // No bean supplies a WorkerAuthorizer, so the default SingleFleetWorkerAuthorizer runs and
        // rejects an unrecognised credential — proving the guard let an own pair through to it.
        mockMvc.perform(put("/worker/runs/" + runA.getId() + "/node-executions/" + execX.getId() + "/status")
                        .header("Authorization", "Bearer not-the-fleet-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"completed\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void escapedExecutionScopedWorkerPath_noBearer_isBadRequest() throws Exception {
        mockMvc.perform(put(URI.create(
                                "/%77orker/runs/" + runA.getId() + "/node-executions/" + execX.getId() + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"completed\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(RunScopeInterceptor.ESCAPED_PATH_MESSAGE));
    }

    @Test
    void unescapedExecutionScopedWorkerPath_withCredential_controlCase_passesToController() throws Exception {
        mockMvc.perform(get("/worker/runs/" + runA.getId() + "/node-executions/" + execX.getId())
                        .header("Authorization", "Bearer test-fleet-token"))
                .andExpect(status().isOk());
    }

    @Test
    void runLevelWorkerRouteEscapedSpelling_isUnaffectedByTheGuard() throws Exception {
        mockMvc.perform(get(URI.create("/%77orker/runs/" + runA.getId() + "/github-token")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void plainOptionsRequest_stillGetsSpringsAllowHeaderAnswer() throws Exception {
        mockMvc.perform(options("/internal/runs/" + runA.getId() + "/node-executions/" + execX.getId() + "/decision")
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andExpect(header().string("Allow", containsString("GET")));
    }

    @Test
    void preHandle_noBestMatchingPatternAttribute_onOptions_passes() {
        RunScopeInterceptor interceptor = new RunScopeInterceptor(mock(NodeExecutionRepository.class));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "OPTIONS", "/internal/runs/" + runA.getId() + "/node-executions/" + execX.getId() + "/decision");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), mock(HandlerMethod.class)))
                .isTrue();
    }

    @Test
    void preHandle_noBestMatchingPatternAttribute_failsClosed() {
        RunScopeInterceptor interceptor = new RunScopeInterceptor(mock(NodeExecutionRepository.class));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/runs/" + runA.getId() + "/node-executions/" + execX.getId() + "/decision");

        assertThatThrownBy(
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), mock(HandlerMethod.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preHandle_misnamedVariable_failsClosed() {
        RunScopeInterceptor interceptor = new RunScopeInterceptor(mock(NodeExecutionRepository.class));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/internal/runs/" + runA.getId() + "/node-executions/" + execX.getId() + "/x");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/internal/runs/{runId}/node-executions/{execId}/x");

        assertThatThrownBy(
                        () -> interceptor.preHandle(request, new MockHttpServletResponse(), mock(HandlerMethod.class)))
                .isInstanceOf(IllegalStateException.class);
    }
}
