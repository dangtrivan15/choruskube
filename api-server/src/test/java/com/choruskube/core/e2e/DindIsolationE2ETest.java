package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.executor.ExecutionParams;
import com.choruskube.core.executor.ExecutionResult;
import com.choruskube.core.executor.WorkloadExecutor;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests verifying that the {@code enableDocker} flag flows correctly from a
 * {@link GitRepo} through {@code GraphSnapshotBuilder} and {@code WorkloadService} all the way to
 * the {@link ExecutionParams} received by the executor.
 *
 * <p>The executor is mocked so no real Docker daemon is required. These tests exercise the full
 * Spring context path: MockMvc → controller → service → JPA → mocked executor.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            // SHA-256 hash of "test-orchestrator-secret"
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
public class DindIsolationE2ETest extends BaseTest {

    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    /** Replaces the real executor bean with a controllable mock. */
    @MockitoBean
    private WorkloadExecutor workloadExecutor;

    private GraphTemplate template;
    private TemplateNode templateNode;

    @BeforeEach
    void setUp() {
        // Seed a minimal template + node definition for the test runs
        template = new GraphTemplate();
        template.setName("DinD Test Template");
        template.setGraphId("dind-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = templateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("dind-test-node");
        nodeDef.setExecutorType(ExecutorType.script);
        nodeDef.setImage("claude-code:e2e");
        nodeDef.setPromptTemplate(null);
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("dind_test_step");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        when(workloadExecutor.execute(any()))
                .thenReturn(new ExecutionResult("dind-test-container-id", "test-secret-hash"));
    }

    @AfterEach
    void cleanUp() {
        execRepo.deleteAll();
        runRepo.deleteAll();
    }

    /**
     * When a run references a GitRepo with {@code enableDocker=true}, the executor must receive
     * {@code ExecutionParams.enableDocker()==true}. This verifies the full pipeline:
     * GitRepo → GraphSnapshotBuilder → WorkloadService → ExecutionParams.
     */
    @Test
    void startWorkload_enableDockerTrue_executorReceivesEnableDockerTrue() throws Exception {
        GitRepo repo = createGitRepo(true);
        NodeExecution nodeExec = createRunAndExecution(repo);

        String body = objectMapper.writeValueAsString(Map.of(
                "templateNodeId", templateNode.getId().toString(),
                "configJson", Map.of("api_server_url", "http://api-server:8080")));

        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", nodeExec.getWorkflowRunId(), nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<ExecutionParams> captor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(workloadExecutor).execute(captor.capture());
        ExecutionParams params = captor.getValue();

        assertThat(params.enableDocker())
                .as("enableDocker must be true when GitRepo.enableDocker=true")
                .isTrue();
    }

    /**
     * When a run references a GitRepo with {@code enableDocker=false} (the default), the executor
     * must receive {@code ExecutionParams.enableDocker()==false} — no DinD sidecar should start.
     */
    @Test
    void startWorkload_enableDockerFalse_executorReceivesEnableDockerFalse() throws Exception {
        GitRepo repo = createGitRepo(false);
        NodeExecution nodeExec = createRunAndExecution(repo);

        String body = objectMapper.writeValueAsString(Map.of(
                "templateNodeId", templateNode.getId().toString(),
                "configJson", Map.of("api_server_url", "http://api-server:8080")));

        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", nodeExec.getWorkflowRunId(), nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<ExecutionParams> captor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(workloadExecutor).execute(captor.capture());
        ExecutionParams params = captor.getValue();

        assertThat(params.enableDocker())
                .as("enableDocker must be false when GitRepo.enableDocker=false")
                .isFalse();
    }

    @Test
    void enableDockerTrue_flowsThroughToExecutionParams() throws Exception {
        GitRepo repo = createGitRepo(true);
        NodeExecution nodeExec = createRunAndExecution(repo);

        String body = objectMapper.writeValueAsString(Map.of(
                "templateNodeId", templateNode.getId().toString(),
                "configJson", Map.of("api_server_url", "http://api-server:8080")));

        mockMvc.perform(post("/internal/workloads/{runId}/{nodeExecId}", nodeExec.getWorkflowRunId(), nodeExec.getId())
                        .header("Authorization", "Bearer " + ORCHESTRATOR_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<ExecutionParams> captor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(workloadExecutor).execute(captor.capture());
        ExecutionParams params = captor.getValue();

        assertThat(params.enableDocker())
                .as("enableDocker must be true when GitRepo.enableDocker=true")
                .isTrue();
    }

    // --- Helpers ---

    private GitRepo createGitRepo(boolean enableDocker) {
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/e2e-dind-test/repo-" + UUID.randomUUID());
        repo.setName(RepoNameUtil.deriveOwnerRepoName(repo.getUrl()));
        repo.setDefaultBranch("main");
        repo.setSecrets("[]");
        repo.setEnableDocker(enableDocker);
        return gitRepoRepo.save(repo);
    }

    private NodeExecution createRunAndExecution(GitRepo repo) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs(objectMapper
                .createObjectNode()
                .put("git_repo_id", repo.getId().toString())
                .toString());
        run = runRepo.save(run);

        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec.setStatus(NodeExecutionStatus.pending);
        return execRepo.save(exec);
    }
}
