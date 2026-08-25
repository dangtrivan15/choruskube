package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.*;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.WorkItemDependencyService;
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

    @Autowired
    private WorkItemDependencyService dependencyService;

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
                .andExpect(jsonPath("$.stage").value("backlog"))
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
    void createFeatureProposal_withPriority_returns201_andCarriesIt() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of(
                "title", "Add dark mode",
                "description", "Users want a dark mode option",
                "priority", "high");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Add dark mode"))
                .andExpect(jsonPath("$.priority").value("high"));
    }

    @Test
    void createFeatureProposal_withoutPriority_defaultsToMedium() throws Exception {
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
                .andExpect(jsonPath("$.priority").value("medium"));
    }

    @Test
    void createFeatureProposal_invalidPriority_returns400() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of(
                "title", "Add dark mode",
                "description", "Users want a dark mode option",
                "priority", "urgent");

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
    void createStory_withPriority_returns201_andCarriesIt() throws Exception {
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

        Map<String, Object> body =
                Map.of("title", "Agent-created story", "description", "Story description", "priority", "low");

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals/" + epicId + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.epicId").value(epicId))
                .andExpect(jsonPath("$.title").value("Agent-created story"))
                .andExpect(jsonPath("$.priority").value("low"));
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

    // ── new no-Epic-ID route: resolves the Epic from the run's own triggering Task ──

    @Test
    void getGraphForCurrentTask_withResolvableTask_returns200AndGraph() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        String createEpicResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Epic for triggering task", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String epicId = objectMapper.readTree(createEpicResponse).get("id").asText();

        String createStoryResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals/" + epicId + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Story for triggering task", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String storyId = objectMapper.readTree(createStoryResponse).get("id").asText();

        String createTaskResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals/" + epicId + "/stories/" + storyId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Triggering task", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String taskId = objectMapper.readTree(createTaskResponse).get("id").asText();

        run.setTaskId(UUID.fromString(taskId));
        run = runRepo.save(run);

        // A cross-Epic blocker of the triggering task (Decision 3), so this also proves the
        // internal/agent-facing graph mirror (`get-roadmap-graph`) carries the same additive
        // `direction`/`internalItemId` shape as the public path, through the HTTP layer.
        String createForeignEpicResponse = mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/"
                                + exec.getId() + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Foreign epic", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String foreignEpicId =
                objectMapper.readTree(createForeignEpicResponse).get("id").asText();

        String createForeignStoryResponse = mockMvc.perform(post("/internal/runs/" + run.getId()
                                + "/node-executions/" + exec.getId() + "/feature-proposals/" + foreignEpicId
                                + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Foreign story", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String foreignStoryId =
                objectMapper.readTree(createForeignStoryResponse).get("id").asText();

        String createForeignTaskResponse = mockMvc.perform(post("/internal/runs/" + run.getId()
                                + "/node-executions/" + exec.getId() + "/feature-proposals/" + foreignEpicId
                                + "/stories/" + foreignStoryId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "Foreign blocking task", "description", "desc"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String foreignTaskId =
                objectMapper.readTree(createForeignTaskResponse).get("id").asText();

        // No internal/agent-facing HTTP endpoint creates dependencies (dependency creation is a
        // user-facing action) — go through the service directly, same as
        // RoadmapGraphServiceTest/RoadmapGraphControllerTest's fixture setup.
        dependencyService.create(
                new CreateDependencyRequest("task", UUID.fromString(foreignTaskId), "task", UUID.fromString(taskId)));

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epic.id").value(epicId))
                .andExpect(jsonPath("$.externalBlockers.length()").value(1))
                .andExpect(jsonPath("$.externalBlockers[0].direction").isNotEmpty())
                .andExpect(jsonPath("$.externalBlockers[0].internalItemId").isNotEmpty())
                .andExpect(jsonPath("$.externalBlockers[0].direction").value("BLOCKING"))
                .andExpect(jsonPath("$.externalBlockers[0].internalItemId").value(taskId));
    }

    @Test
    void getGraphForCurrentTask_runWithNoTaskId_returns404() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        // The `@BeforeEach` run fixture never sets `task_id` — this is a manually-started run.
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/graph"))
                .andExpect(status().isNotFound());
    }

    // ── input-artifact manifest: what the orchestrator merges into config.json ─────
    //
    // The declared arm is covered against mocked repositories in ArtifactResolutionServiceTest;
    // these drive the same resolution through the real endpoint, real repositories and real
    // JSON serialization, so a break in the wiring (or in the response shape the orchestrator
    // parses) fails here rather than only in E2E. The human-gate passthrough arm is
    // deliberately left to ArtifactResolutionServiceTest — it needs traversed_edge_ids plus a
    // graph snapshot carrying edges, which is a brittle fixture to build through this class.

    @Test
    void getInputArtifacts_declaredArtifact_joinsNameOntoSourceOutputPrefix() throws Exception {
        TemplateNode producer = saveTemplateNode("spec_review", null);
        TemplateNode consumer = saveTemplateNode(
                "implement",
                "[{\"template_node_label\":\"spec_review\",\"artifacts\":"
                        + "[{\"name\":\"spec_and_plan.md\",\"description\":\"The approved spec\",\"required\":true}]}]");

        String prefix = "system/runs/" + run.getId() + "/spec-review-exec/out/";
        saveCompletedExec(producer, "{\"output\":\"" + prefix + "\"}");
        NodeExecution consumerExec = saveExec(consumer);

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + consumerExec.getId()
                        + "/input-artifacts"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.artifacts['spec_review/spec_and_plan.md']").value(prefix + "spec_and_plan.md"))
                .andExpect(jsonPath("$.required.length()").value(1))
                .andExpect(jsonPath("$.required[0]").value("spec_review/spec_and_plan.md"));
    }

    @Test
    void getInputArtifacts_optionalDeclarations_appearInArtifactsButNotRequired() throws Exception {
        TemplateNode producer = saveTemplateNode("spec_review", null);
        // Two shapes of "optional": explicitly required:false, and no `required` key at all.
        // The second is the iteration-1 case — a declaration naming a prior iteration's file
        // that does not exist yet must not harden into a pod abort.
        TemplateNode consumer = saveTemplateNode(
                "implement",
                "[{\"template_node_label\":\"spec_review\",\"artifacts\":["
                        + "{\"name\":\"explicitly_optional.md\",\"description\":\"d\",\"required\":false},"
                        + "{\"name\":\"unflagged.md\",\"description\":\"d\"}]}]");

        String prefix = "system/runs/" + run.getId() + "/spec-review-exec/out/";
        saveCompletedExec(producer, "{\"output\":\"" + prefix + "\"}");
        NodeExecution consumerExec = saveExec(consumer);

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + consumerExec.getId()
                        + "/input-artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifacts['spec_review/explicitly_optional.md']")
                        .value(prefix + "explicitly_optional.md"))
                .andExpect(jsonPath("$.artifacts['spec_review/unflagged.md']").value(prefix + "unflagged.md"))
                .andExpect(jsonPath("$.required.length()").value(0));
    }

    @Test
    void getInputArtifacts_nodeWithNoDeclarations_returnsEmptyManifest() throws Exception {
        // The `@BeforeEach` template node declares nothing — the overwhelmingly common case.
        // It must produce an empty manifest, not an error: the orchestrator calls this endpoint
        // for every node execution.
        NodeExecution exec = saveExec(templateNode);

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/input-artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifacts").isEmpty())
                .andExpect(jsonPath("$.required.length()").value(0));
    }

    private TemplateNode saveTemplateNode(String label, String requiredInputArtifacts) {
        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(templateNode.getNodeDefinitionId());
        tn.setLabel(label);
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(false);
        tn.setRequiredInputArtifacts(requiredInputArtifacts);
        return templateNodeRepo.save(tn);
    }

    private NodeExecution saveExec(TemplateNode tn) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(tn.getId());
        exec.setGraphVersion(1);
        return execRepo.save(exec);
    }

    /** A finished producer: only completed executions supply an output prefix to resolve against. */
    private NodeExecution saveCompletedExec(TemplateNode tn, String artifactRefs) {
        NodeExecution exec = saveExec(tn);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setArtifactRefs(artifactRefs);
        return execRepo.save(exec);
    }

    // ── node-execution-scoped pull-requests mirror (Decision 3/3.3) ──────────────

    @Test
    void getPullRequestsForNodeExecution_returnsSamePullRequestsAsRunScopedRead() throws Exception {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        Map<String, Object> body = Map.of(
                "gitRepoId",
                gitRepo.getId().toString(),
                "prUrl",
                "https://github.com/test/repo/pull/1",
                "prNumber",
                1,
                "title",
                "Add dark mode",
                "repoName",
                gitRepo.getName());

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/pull-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // The node-execution-scoped mirror exists precisely because InternalAuthFilter only
        // authorizes an agent's JOB_SECRET for paths carrying its own node-executions/{id}
        // segment — the plain run-scoped GET /{runId}/pull-requests is unreachable from an
        // agent pod. It must return the exact same PR set.
        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/pull-requests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].prUrl").value("https://github.com/test/repo/pull/1"))
                .andExpect(jsonPath("$[0].gitRepoId").value(gitRepo.getId().toString()))
                .andExpect(jsonPath("$[0].workflowRunId").value(run.getId().toString()));
    }

    // ── stale run-branch cleanup (Part 2) ─────────────────────────────────────────
    //
    // Auth-scoping for this run-level, orchestrator-only endpoint (ORCHESTRATOR_SECRET vs. an
    // agent JOB_SECRET vs. no auth at all) is covered in InternalAuthFilterTest, which already
    // configures internal.auth.mode=enforce with a real orchestrator-secret-hash — this class's
    // fixtures run with no configured hash (auth filter passes everything through), so it is the
    // right place to prove the endpoint's own functional shape instead.

    @Test
    void cleanupBranches_returns200WithASummaryPerRepo() throws Exception {
        // No real GitHub credential is configured in this test environment, so
        // BranchCleanupService's per-repo GitHub call fails — but that failure is caught and
        // reported as a per-repo outcome, never surfaced as an HTTP error: the endpoint is
        // best-effort and must always answer 200 with one result per repo in the run.
        mockMvc.perform(post("/internal/runs/" + run.getId() + "/cleanup-branches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(
                        jsonPath("$.results[0].gitRepoId").value(gitRepo.getId().toString()))
                .andExpect(jsonPath("$.results[0].branch").value("choruskube-run-" + run.getId()))
                .andExpect(jsonPath("$.results[0].outcome").value("SKIPPED_ERROR"));
    }
}
