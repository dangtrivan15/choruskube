package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.*;
import com.choruskube.core.service.EpicService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class InternalRunControllerTest extends BaseTest {

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
    private EpicService epicService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private GraphTemplate template;
    private TemplateNode templateNode;
    private WorkflowRun run;
    private GitRepo gitRepo;

    @BeforeEach
    void setUp() {
        gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/test/repo");
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/test/repo"));
        gitRepo.setTestCommand("npm test");
        gitRepo.setAgentImage("test:latest");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        template = new GraphTemplate();
        template.setName("Test Template");
        template.setGraphId("test-template");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("test-node");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{\"git_repo_id\":\"" + gitRepo.getId() + "\"}");
        run = runRepo.save(run);
    }

    @Test
    void createNodeExecution_returns201() throws Exception {
        Map<String, Object> body = Map.of(
                "templateNodeId", templateNode.getId().toString(),
                "graphVersion", 1,
                "iteration", 1);

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.iteration").value(1));
    }

    @Test
    void getRunStatus_returnsPendingByDefault() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void getRunStatus_returnsRunningAfterUpdate() throws Exception {
        Map<String, Object> body = Map.of("status", "running");
        mockMvc.perform(put("/internal/runs/" + run.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void updateRunStatus_returns204() throws Exception {
        Map<String, Object> body = Map.of("status", "running");

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());
    }

    @Test
    void getGraphRuntimeSnapshot_returnsJson() throws Exception {
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/graph-runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.edges").isArray());
    }

    @Test
    void updateNodeExecutionStatus_returnsUpdated() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of("status", "running");

        mockMvc.perform(put("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void writeExecutionLog_returns201() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of("level", "info", "message", "Test log message");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    // ── existing feature-proposals path — now produces an Epic (Decision 6: path unchanged) ──

    @Test
    void createFeatureProposal_returns201_andProducesAnEpic() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of(
                "title", "Add dark mode",
                "description", "Users want a dark mode option",
                "motivation", "Improves accessibility");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Add dark mode"))
                .andExpect(jsonPath("$.description").value("Users want a dark mode option"))
                .andExpect(jsonPath("$.motivation").value("Improves accessibility"))
                .andExpect(jsonPath("$.status").value("backlog"))
                .andExpect(
                        jsonPath("$.softwareProject.id").value(gitRepo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andExpect(jsonPath("$.repos[0].id").value(gitRepo.getId().toString()));
    }

    @Test
    void createFeatureProposal_withoutMotivation_returns201() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of(
                "title", "Add dark mode",
                "description", "Users want a dark mode option");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Add dark mode"))
                .andExpect(jsonPath("$.motivation").doesNotExist());
    }

    @Test
    void createFeatureProposal_missingTitle_returns400() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of("description", "Some description");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listFeatureProposals_returnsEmptyArray() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        mockMvc.perform(get(
                        "/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/feature-proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void updateFeatureProposal_returns200_withUpdatedTitle() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        // Create a proposal first via the create endpoint.
        Map<String, Object> createBody = Map.of(
                "title", "Original Title",
                "description", "Original description");
        String createResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String proposalId = objectMapper.readTree(createResponse).get("id").asText();

        // Now PATCH the title only.
        Map<String, Object> updateBody = Map.of("title", "Updated Title");
        mockMvc.perform(patch("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals/" + proposalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(proposalId))
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.description").value("Original description"));
    }

    @Test
    void updateFeatureProposal_withUnknownProposalId_returns404() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        String unknownId = UUID.randomUUID().toString();
        Map<String, Object> updateBody = Map.of("title", "New Title");

        mockMvc.perform(patch("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals/" + unknownId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateFeatureProposal_withProposalFromDifferentProject_returns403() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        // Create a second GitRepo owned by the same org but different from the run's project.
        GitRepo gitRepo2 = new GitRepo();
        gitRepo2.setUrl("https://github.com/test/other-repo");
        gitRepo2.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/test/other-repo"));
        gitRepo2 = gitRepoRepo.save(gitRepo2);

        // Create an Epic targeting gitRepo2 directly via the service (bypassing the run).
        EpicResponse otherProposal =
                epicService.create(new EpicRequest("Other Project Proposal", "desc", null, gitRepo2.getId()), null);

        // Try to update it via the run, which resolves to gitRepo (not gitRepo2) → 403.
        Map<String, Object> updateBody = Map.of("title", "Hijacked Title");
        mockMvc.perform(patch("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals/" + otherProposal.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listFeatureProposals_returnsCreatedProposals() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        // Create a proposal first
        Map<String, Object> body = Map.of(
                "title", "Test proposal",
                "description", "Test description",
                "motivation", "Test motivation");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // Now list proposals
        mockMvc.perform(get(
                        "/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/feature-proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Test proposal"))
                .andExpect(jsonPath("$[0].description").value("Test description"))
                .andExpect(jsonPath("$[0].repos[0].url").value("https://github.com/test/repo"));
    }

    // ── new nested Story/Task creation paths (Decision 6/3.6) ─────────────────────

    @Test
    void createStory_returns201() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        String createEpicResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Epic for story", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String epicId = objectMapper.readTree(createEpicResponse).get("id").asText();

        Map<String, Object> body = Map.of("title", "Agent-created story", "description", "Story description");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals/" + epicId + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.epicId").value(epicId))
                .andExpect(jsonPath("$.title").value("Agent-created story"));
    }

    @Test
    void createTask_returns201() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        String createEpicResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Epic for task", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String epicId = objectMapper.readTree(createEpicResponse).get("id").asText();

        String createStoryResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals/" + epicId + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Story for task", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String storyId = objectMapper.readTree(createStoryResponse).get("id").asText();

        Map<String, Object> body = Map.of("title", "Agent-created task", "description", "Task description");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals/" + epicId + "/stories/" + storyId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storyId").value(storyId))
                .andExpect(jsonPath("$.title").value("Agent-created task"))
                .andExpect(jsonPath("$.status").value("backlog"))
                .andExpect(
                        jsonPath("$.softwareProject.id").value(gitRepo.getId().toString()));
    }

    @Test
    void createTask_withEpicIdNotMatchingStorysActualEpic_returns404() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        String createEpicResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Real epic", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String epicId = objectMapper.readTree(createEpicResponse).get("id").asText();

        String createStoryResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals/" + epicId + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Story under real epic", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String storyId = objectMapper.readTree(createStoryResponse).get("id").asText();

        // A different, unrelated Epic id in the URL — the Story above does not belong to it.
        String otherEpicResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Unrelated epic", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unrelatedEpicId =
                objectMapper.readTree(otherEpicResponse).get("id").asText();

        Map<String, Object> body = Map.of("title", "Should not be created", "description", "desc");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals/" + unrelatedEpicId + "/stories/" + storyId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }
}
