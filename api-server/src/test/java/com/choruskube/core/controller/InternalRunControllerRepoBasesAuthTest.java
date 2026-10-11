package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.InternalAuthFilter;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /internal/runs/{runId}/node-executions/{nodeExecId}/repo-bases} with internal auth
 * enforced: an agent records only its own execution's repo bases. Split from {@link
 * InternalRunControllerTest} for the same reason as {@link InternalRunControllerPullRequestsAuthTest}.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class InternalRunControllerRepoBasesAuthTest extends BaseTest {

    private static final String JOB_SECRET = "test-repo-base-job-secret";
    private static final String OTHER_RUN_JOB_SECRET = "test-repo-base-other-run-job-secret";

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

    @Autowired
    private NodeExecutionRepoBaseRepository baseRepo;

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
        gitRepo.setUrl("https://github.com/test/repo-base-auth-repo");
        gitRepo.setName("test/repo-base-auth-repo");
        gitRepo.setTestCommand("npm test");
        gitRepo.setAgentImage("test:latest");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        GraphTemplate template = new GraphTemplate();
        template.setName("Repo Base Auth Test Template");
        template.setGraphId("repo-base-auth-test-template");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("repo-base-auth-test-node");
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
        templateNode.setLabel("Repo Base Auth Test Node");
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
    void ownJobSecret_recordsAndUpserts() throws Exception {
        post(run.getId(), exec.getId(), JOB_SECRET, body("clean", "sha-1")).andExpect(status().isNoContent());
        post(run.getId(), exec.getId(), JOB_SECRET, body("clean", "sha-2")).andExpect(status().isNoContent());

        assertThat(baseRepo.findByNodeExecutionId(exec.getId()))
                .singleElement()
                .satisfies(b -> assertThat(b.getBaseSha()).isEqualTo("sha-2"));
    }

    @Test
    void conflict_dropsAnyShaAndKeepsTheFiles() throws Exception {
        post(run.getId(), exec.getId(), JOB_SECRET, body("conflict", "ignored")).andExpect(status().isNoContent());

        NodeExecutionRepoBase row = baseRepo.findByNodeExecutionId(exec.getId()).get(0);
        assertThat(row.getBaseSha()).isNull();
        assertThat(row.getConflictedFiles()).contains("a.txt");
    }

    @Test
    void unknownOutcome_isBadRequest() throws Exception {
        post(run.getId(), exec.getId(), JOB_SECRET, body("merged", "x")).andExpect(status().isBadRequest());
        assertThat(baseRepo.findByNodeExecutionId(exec.getId())).isEmpty();
    }

    @Test
    void cleanWithoutSha_isBadRequest() throws Exception {
        post(run.getId(), exec.getId(), JOB_SECRET, body("clean", null)).andExpect(status().isBadRequest());
    }

    @Test
    void anotherExecutionsSecret_isUnauthorized() throws Exception {
        NodeExecution otherExec = new NodeExecution();
        otherExec.setWorkflowRunId(run.getId());
        otherExec.setTemplateNodeId(exec.getTemplateNodeId());
        otherExec.setGraphVersion(1);
        otherExec.setJobSecretHash(InternalAuthFilter.sha256Hex(OTHER_RUN_JOB_SECRET));
        execRepo.save(otherExec);

        post(run.getId(), exec.getId(), OTHER_RUN_JOB_SECRET, body("clean", "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownSecretButUnrelatedRunId_isNotFound() throws Exception {
        WorkflowRun unrelatedRun = new WorkflowRun();
        unrelatedRun.setGraphTemplateId(run.getGraphTemplateId());
        unrelatedRun = runRepo.save(unrelatedRun);

        post(unrelatedRun.getId(), exec.getId(), JOB_SECRET, body("clean", "x")).andExpect(status().isNotFound());
    }

    private ResultActions post(UUID runId, UUID execId, String secret, String body) throws Exception {
        return mockMvc.perform(
                MockMvcRequestBuilders.post("/internal/runs/" + runId + "/node-executions/" + execId + "/repo-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + secret));
    }

    private String body(String outcome, String sha) {
        return "{\"repos\":[{\"gitRepoId\":\"" + gitRepo.getId() + "\",\"outcome\":\"" + outcome
                + "\",\"baseSha\":" + (sha == null ? "null" : "\"" + sha + "\"")
                + ",\"conflictedFiles\":[\"a.txt\"]}]}";
    }
}
