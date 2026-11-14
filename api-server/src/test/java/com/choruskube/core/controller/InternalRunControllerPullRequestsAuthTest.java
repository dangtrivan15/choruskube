package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.InternalAuthFilter;
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
 * Auth-scoped coverage for {@code GET /internal/runs/{runId}/node-executions/{nodeExecId}/pull-requests}
 * (the node-execution-scoped PR-read mirror an agent's {@code
 * JOB_SECRET} can actually reach, since {@link InternalAuthFilter} only authorizes a path
 * carrying its own {@code node-executions/{nodeExecId}} segment).
 *
 * <p>Deliberately split from {@link InternalRunControllerTest} rather than added there: that
 * class's whole suite issues every {@code mockMvc} call with no {@code Authorization} header,
 * relying on {@code internal.auth.orchestrator-secret-hash} being unset so {@link
 * InternalAuthFilter} passes every request through unauthenticated. Adding a class-level {@code
 * @TestPropertySource} enabling enforcement there would force every one of that class's other,
 * unrelated tests to start sending a Bearer token too. This mirrors the same split already used
 * for {@link InternalArtifactControllerTest} (presign) and {@link RunControllerAuthTest}
 * (public API auth) — each gets its own Spring context with auth actually turned on.
 *
 * <p>Two shapes of the same rejection are covered here, both now refused by {@link
 * InternalAuthFilter} itself: {@link
 * #getPullRequestsForNodeExecution_withJobSecretFromDifferentRunsExecution_isUnauthorized} (a
 * token that doesn't match the path's {@code nodeExecId} at all) and {@link
 * #getPullRequestsForNodeExecution_withOwnJobSecretButUnrelatedRunId_isUnauthorized} (a caller's
 * own valid {@code nodeExecId}, paired with someone else's {@code runId} — the filter now compares
 * the authenticated execution's {@code workflow_run_id} against the path's {@code runId} too).
 * The service-layer cross-check {@code RunPullRequestService#getPullRequestsForNodeExecution} adds
 * on top of the filter remains as the backstop for the orchestrator tier, which the filter never
 * checks against the run.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class InternalRunControllerPullRequestsAuthTest extends BaseTest {

    private static final String JOB_SECRET = "test-pr-read-job-secret";
    private static final String OTHER_RUN_JOB_SECRET = "test-pr-read-other-run-job-secret";

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

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private WorkflowRun run;
    private NodeExecution exec;
    private GitRepo gitRepo;

    @BeforeEach
    void setUp() {
        gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/test/pr-auth-repo");
        gitRepo.setName("test/pr-auth-repo");
        gitRepo.setTestCommand("npm test");
        gitRepo.setAgentImage("test:latest");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        GraphTemplate template = new GraphTemplate();
        template.setName("PR Auth Test Template");
        template.setGraphId("pr-auth-test-template");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("pr-auth-test-node");
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
        templateNode.setLabel("PR Auth Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        // Resolved by GraphSnapshotBuilder#reposForRun's legacy single-repo fallback, so this
        // run's own repository is gitRepo — the same repo pull-request registration must accept.
        run.setInputs("{\"git_repo_id\":\"" + gitRepo.getId() + "\"}");
        run = runRepo.save(run);

        exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec.setJobSecretHash(InternalAuthFilter.sha256Hex(JOB_SECRET));
        exec = execRepo.save(exec);
    }

    @Test
    void getPullRequestsForNodeExecution_withOwnJobSecret_returnsRunsPullRequests() throws Exception {
        Map<String, Object> body = Map.of(
                "gitRepoId",
                gitRepo.getId().toString(),
                "prUrl",
                "https://github.com/test/pr-auth-repo/pull/7",
                "prNumber",
                7,
                "title",
                "Implement feature",
                "repoName",
                gitRepo.getName());

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/pull-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/pull-requests")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].prUrl").value("https://github.com/test/pr-auth-repo/pull/7"));
    }

    @Test
    void getPullRequestsForNodeExecution_withJobSecretFromDifferentRunsExecution_isUnauthorized() throws Exception {
        // A second run with its own node execution and its own JOB_SECRET. InternalAuthFilter
        // extracts the nodeExecId named in the URL path and checks the presented Bearer token's
        // hash against THAT execution's own stored hash — it never cross-checks the runId
        // segment against the execution's actual workflow_run_id. So the only way to actually
        // exercise a rejection here is a token that doesn't match the *path's own* nodeExecId:
        // this run's own execution in the path, but a different run's execution's JOB_SECRET as
        // the token.
        GraphTemplate otherTemplate = new GraphTemplate();
        otherTemplate.setName("Other PR Auth Test Template");
        otherTemplate.setGraphId("other-pr-auth-test-template");
        otherTemplate.setVersion(1);
        otherTemplate = graphTemplateRepo.save(otherTemplate);

        NodeDefinition otherNodeDef = new NodeDefinition();
        otherNodeDef.setName("other-pr-auth-test-node");
        otherNodeDef.setExecutorType(ExecutorType.ai);
        otherNodeDef.setImage("test:latest");
        otherNodeDef.setPromptTemplate("test");
        otherNodeDef.setSkills("[]");
        otherNodeDef.setInputSpec("{}");
        otherNodeDef.setOutputSpec("{}");
        otherNodeDef.setSecrets("[]");
        otherNodeDef = nodeDefRepo.save(otherNodeDef);

        TemplateNode otherTemplateNode = new TemplateNode();
        otherTemplateNode.setGraphTemplateId(otherTemplate.getId());
        otherTemplateNode.setNodeDefinitionId(otherNodeDef.getId());
        otherTemplateNode.setLabel("Other PR Auth Test Node");
        otherTemplateNode.setConfigOverrides("{}");
        otherTemplateNode.setEntrypoint(true);
        otherTemplateNode = templateNodeRepo.save(otherTemplateNode);

        WorkflowRun otherRun = new WorkflowRun();
        otherRun.setGraphTemplateId(otherTemplate.getId());
        otherRun = runRepo.save(otherRun);

        NodeExecution otherExec = new NodeExecution();
        otherExec.setWorkflowRunId(otherRun.getId());
        otherExec.setTemplateNodeId(otherTemplateNode.getId());
        otherExec.setGraphVersion(1);
        otherExec.setJobSecretHash(InternalAuthFilter.sha256Hex(OTHER_RUN_JOB_SECRET));
        execRepo.save(otherExec);

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/pull-requests")
                        .header("Authorization", "Bearer " + OTHER_RUN_JOB_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getPullRequestsForNodeExecution_withOwnJobSecretButUnrelatedRunId_isUnauthorized() throws Exception {
        // Present this execution's OWN valid JOB_SECRET but substitute a different, unrelated
        // run's UUID for the runId segment. InternalAuthFilter now refuses this itself, comparing
        // the authenticated execution's own run against the path's run, before the controller or
        // its service-layer cross-check ever run.
        WorkflowRun unrelatedRun = new WorkflowRun();
        unrelatedRun.setGraphTemplateId(run.getGraphTemplateId());
        unrelatedRun = runRepo.save(unrelatedRun);

        mockMvc.perform(get("/internal/runs/" + unrelatedRun.getId() + "/node-executions/" + exec.getId()
                                + "/pull-requests")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createPullRequest_withRepoOutsideRun_isNotFound_andWritesNoRow() throws Exception {
        // A real, existing repo — but not one of this run's own (run's git_repo_id is gitRepo).
        GitRepo foreignRepo = new GitRepo();
        foreignRepo.setUrl("https://github.com/test/foreign-repo");
        foreignRepo.setName("test/foreign-repo");
        foreignRepo.setTestCommand("npm test");
        foreignRepo.setAgentImage("test:latest");
        foreignRepo.setSecrets("[]");
        foreignRepo = gitRepoRepo.save(foreignRepo);

        Map<String, Object> body = Map.of(
                "gitRepoId",
                foreignRepo.getId().toString(),
                "prUrl",
                "https://github.com/test/foreign-repo/pull/1",
                "prNumber",
                1,
                "title",
                "Unrelated change",
                "repoName",
                foreignRepo.getName());

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/pull-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/pull-requests")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
