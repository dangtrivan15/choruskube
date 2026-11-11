package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.PrepareWorkloadResponse;
import com.choruskube.core.executor.WorkloadAgentCredentialResolver;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * A single-tenant server holds no credential even when its environment still sets the settings it
 * used to read: nothing resolves them, prepare attaches nothing, and no route returns a credential.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "CLAUDE_CODE_OAUTH_TOKEN=sk-ant-oat01-retired-setting-not-read",
            "GITHUB_PAT=ghp_retired_setting_not_read",
            "GITHUB_APP_ID=12345",
            "GITHUB_APP_INSTALLATION_ID=67890",
            "GITHUB_APP_PRIVATE_KEY_PATH=/nonexistent/retired-setting.pem",
            "worker.registration.token=test-fleet"
        })
class RetiredCredentialSettingsIntegrationTest extends BaseTest {

    @Autowired
    private ApplicationContext context;

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

    private GitRepo repo;
    private TemplateNode claudeNode;
    private WorkflowRun run;
    private NodeExecution exec;

    @BeforeEach
    void setUp() {
        repo = new GitRepo();
        repo.setUrl("https://github.com/retired-settings-test/repo-" + UUID.randomUUID());
        repo.setName(repo.getUrl());
        repo.setSecrets("[]");
        repo = gitRepoRepo.save(repo);

        GraphTemplate template = new GraphTemplate();
        template.setName("Retired Credential Settings Template");
        template.setGraphId("retired-credential-settings-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("retired-credential-settings-node-" + UUID.randomUUID());
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        claudeNode = new TemplateNode();
        claudeNode.setGraphTemplateId(template.getId());
        claudeNode.setNodeDefinitionId(nodeDef.getId());
        claudeNode.setLabel("Claude Node");
        claudeNode.setConfigOverrides("{}");
        claudeNode.setEntrypoint(true);
        claudeNode = templateNodeRepo.save(claudeNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{\"git_repo_id\":\"" + repo.getId() + "\"}");
        run = runRepo.save(run);

        exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(claudeNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);
    }

    @Test
    void noCredentialResolverIsRegistered() {
        assertThat(context.getBeanNamesForType(WorkloadAgentCredentialResolver.class))
                .isEmpty();
        assertThat(context.getBeanNamesForType(GitHubCredentialResolver.class)).isEmpty();
    }

    @Test
    void prepareForAClaudeNode_attachesNoClaudeTokenAndNoRegistryLogin() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("templateNodeId", claudeNode.getId(), "configJson", Map.of("executor_type", "ai")));

        String json = mockMvc.perform(
                        post("/worker/runs/{r}/node-executions/{e}/workload/prepare", run.getId(), exec.getId())
                                .header("Authorization", "Bearer test-fleet")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        PrepareWorkloadResponse response = objectMapper.readValue(json, PrepareWorkloadResponse.class);
        assertThat(response.image()).isEqualTo("test:latest");
        assertThat(response.claudeOAuthToken()).isNull();
        assertThat(response.registryCredentials()).isNull();
    }

    @Test
    void brokerRoute_isNotServed() throws Exception {
        mockMvc.perform(get("/worker/runs/{r}/github-token", run.getId()).header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isNotFound());
    }

    @Test
    void tokenScopeRoute_answersWithRepositoriesAndPermissions() throws Exception {
        String ownerRepo = RepoNameUtil.githubOwnerRepo(repo.getUrl()).orElseThrow();

        mockMvc.perform(get("/worker/runs/{r}/github-token-scope", run.getId())
                        .header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories", contains(ownerRepo)))
                .andExpect(jsonPath("$.permissions.contents").value("write"));
    }

    @Test
    void tokenScopeRoute_withoutCredential_isUnauthorized() throws Exception {
        mockMvc.perform(get("/worker/runs/{r}/github-token-scope", run.getId())).andExpect(status().isUnauthorized());
    }
}
