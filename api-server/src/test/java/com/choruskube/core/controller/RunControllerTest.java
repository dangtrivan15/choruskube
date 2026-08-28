package com.choruskube.core.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class RunControllerTest extends BaseTest {

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
    private StoryRepository storyRepo;

    @Autowired
    private com.choruskube.core.repository.EpicRepository epicRepo;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private SoftwareProjectRepository softwareProjectRepo;

    @Autowired
    private TemplateEdgeRepository templateEdgeRepo;

    @Autowired
    private NodeExecutionRepository nodeExecutionRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @BeforeEach
    void setUp() {
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void getRun_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/runs/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isNotFound());
    }

    @Test
    void startRun_createsRunAndReturns201() throws Exception {
        GraphTemplate template = new GraphTemplate();
        template.setName("Test Template");
        template.setGraphId("test-template");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("test-ai-node");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("registry.example.com/claude-code:latest");
        nodeDef.setPromptTemplate("Write hello world");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nodeDef.getId());
        tn.setLabel("Test Node");
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(true);
        templateNodeRepo.save(tn);

        Map<String, Object> body = Map.of("graphTemplateId", template.getId());

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.graphTemplateId").value(template.getId().toString()))
                .andExpect(jsonPath("$.templateName").value("Test Template"))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void startRun_withName_persitsName() throws Exception {
        GraphTemplate template = new GraphTemplate();
        template.setName("Named Template");
        template.setGraphId("named-template");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("test-ai-node-named");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("registry.example.com/claude-code:latest");
        nodeDef.setPromptTemplate("Write hello world");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nodeDef.getId());
        tn.setLabel("Test Node");
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(true);
        templateNodeRepo.save(tn);

        Map<String, Object> body = Map.of("graphTemplateId", template.getId(), "name", "Add dark mode");

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Add dark mode"));
    }

    @Test
    void renameRun_happyPath_returns204() throws Exception {
        GraphTemplate template = new GraphTemplate();
        template.setName("Template for rename");
        template.setGraphId("rename-template");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("Old Name");
        run = runRepo.save(run);

        Map<String, String> body = Map.of("name", "New Name");

        mockMvc.perform(patch("/api/v1/runs/" + run.getId() + "/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNoContent());

        // Verify the name was updated
        mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void renameRun_notFound_returns404() throws Exception {
        Map<String, String> body = Map.of("name", "New Name");

        mockMvc.perform(patch("/api/v1/runs/00000000-0000-0000-0000-000000000099/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void renameRun_blankName_returns400() throws Exception {
        Map<String, String> body = Map.of("name", "  ");

        mockMvc.perform(patch("/api/v1/runs/00000000-0000-0000-0000-000000000001/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/runs/{id} — task field
    // -----------------------------------------------------------------------

    private GraphTemplate createTemplate(String name, String graphId) {
        GraphTemplate template = new GraphTemplate();
        template.setName(name);
        template.setGraphId(graphId);
        template.setVersion(1);
        return graphTemplateRepo.save(template);
    }

    private WorkflowRun createRun(GraphTemplate template) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        return runRepo.save(run);
    }

    /** Creates an Epic -> Story -> Task chain targeting {@code softwareProjectId} and returns the Task. */
    private Task createTaskChain(java.util.UUID softwareProjectId, String title, WorkItemStatus status) {
        com.choruskube.core.model.Epic epic = new com.choruskube.core.model.Epic();
        epic.setTitle(title);
        epic.setDescription("desc");
        epic.setSoftwareProjectId(softwareProjectId);
        epic = epicRepo.save(epic);

        Story story = new Story();
        story.setEpicId(epic.getId());
        story.setTitle(title);
        story.setDescription("desc");
        story = storyRepo.save(story);

        Task task = new Task();
        task.setStoryId(story.getId());
        task.setTitle(title);
        task.setDescription("desc");
        task.setSoftwareProjectId(softwareProjectId);
        task.setStatus(status);
        return taskRepo.save(task);
    }

    @Test
    void getRun_noLinkedTask_taskIsNull() throws Exception {
        GraphTemplate template = createTemplate("FP Test Template", "fp-test-template");
        WorkflowRun run = createRun(template);

        mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").value(nullValue()));
    }

    @Test
    void getRun_withLinkedTask_returnsRunTaskSummary() throws Exception {
        GraphTemplate template = createTemplate("FP Template With Task", "fp-template-with-task");
        WorkflowRun run = createRun(template);

        GitRepo gitRepo = new GitRepo();
        gitRepo.setName("my-api-repo");
        gitRepo.setUrl("https://github.com/example/my-api-repo");
        gitRepo = (GitRepo) softwareProjectRepo.save(gitRepo);

        Task task = createTaskChain(gitRepo.getId(), "Add dark mode", WorkItemStatus.in_progress);
        run.setTaskId(task.getId());
        runRepo.save(run);

        mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").isNotEmpty())
                .andExpect(jsonPath("$.task.title").value("Add dark mode"))
                .andExpect(jsonPath("$.task.status").value("in_progress"))
                .andExpect(jsonPath("$.task.softwareProject.name").value("my-api-repo"))
                .andExpect(jsonPath("$.task.softwareProject.type").value("git_repo"));
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/runs/{id} — promptText field
    // -----------------------------------------------------------------------

    @Test
    void getRun_templateDeclaresPromptKey_returnsPromptText() throws Exception {
        GraphTemplate template = createTemplate("Prompt Template", "prompt-template");
        template.setPromptInputKey("feature_request");
        graphTemplateRepo.save(template);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{\"feature_request\":\"Add dark mode toggle\"}");
        run = runRepo.save(run);

        mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promptText").value("Add dark mode toggle"));
    }

    @Test
    void getRun_templateWithoutPromptKey_promptTextIsNull() throws Exception {
        GraphTemplate template = createTemplate("No-Prompt Template", "no-prompt-template");
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{\"feature_request\":\"ignored because key is null\"}");
        run = runRepo.save(run);

        mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promptText").value(nullValue()));
    }

    // -----------------------------------------------------------------------
    // softwareProject top-level field
    // -----------------------------------------------------------------------

    @Test
    void getRun_withLinkedTask_includesSoftwareProject() throws Exception {
        GraphTemplate template = createTemplate("SP Test Template", "sp-test-template");
        WorkflowRun run = createRun(template);

        GitRepo gitRepo = new GitRepo();
        gitRepo.setName("my-software-repo");
        gitRepo.setUrl("https://github.com/example/my-software-repo");
        gitRepo = (GitRepo) softwareProjectRepo.save(gitRepo);

        Task task = createTaskChain(gitRepo.getId(), "Feature from task", WorkItemStatus.in_progress);
        run.setTaskId(task.getId());
        runRepo.save(run);

        mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.softwareProject.name").value("my-software-repo"))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andExpect(jsonPath("$.task.softwareProject.name").value("my-software-repo"));
    }

    @Test
    void listRuns_withTask_includesSoftwareProject() throws Exception {
        GraphTemplate template = createTemplate("SP List Template", "sp-list-template");
        WorkflowRun run = createRun(template);

        GitRepo gitRepo = new GitRepo();
        gitRepo.setName("list-software-repo");
        gitRepo.setUrl("https://github.com/example/list-software-repo");
        gitRepo = (GitRepo) softwareProjectRepo.save(gitRepo);

        Task task = createTaskChain(gitRepo.getId(), "Feature for listing", WorkItemStatus.backlog);
        run.setTaskId(task.getId());
        runRepo.save(run);

        mockMvc.perform(get("/api/v1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + run.getId() + "')].softwareProject.name")
                        .value("list-software-repo"));
    }

    @Test
    void getRun_noTaskNoInputs_softwareProjectIsNull() throws Exception {
        GraphTemplate template = createTemplate("No-SP Template", "no-sp-template");
        WorkflowRun run = createRun(template);

        mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.softwareProject").value(nullValue()));
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/runs/{id} — graphSnapshot.nodes[].decision_options parity
    // with GET /api/v1/pending-gates' content[].decisionOptions
    // (both endpoints must resolve the same node's decision options via the
    // shared DecisionOptionsResolver, so the run-page sidebar and the
    // Approvals page can never silently disagree on which decisions a gate
    // accepts).
    // -----------------------------------------------------------------------

    private NodeDefinition createHumanGateNodeDefinition(String name) {
        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName(name);
        nodeDef.setExecutorType(ExecutorType.human);
        nodeDef.setImage("placeholder");
        nodeDef.setPromptTemplate("");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef.setTimeoutSeconds(1800);
        return nodeDefRepo.save(nodeDef);
    }

    private TemplateNode createTemplateNode(
            GraphTemplate template, NodeDefinition nodeDef, String label, String configOverrides, boolean entrypoint) {
        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nodeDef.getId());
        tn.setLabel(label);
        tn.setConfigOverrides(configOverrides);
        tn.setEntrypoint(entrypoint);
        return templateNodeRepo.save(tn);
    }

    private TemplateEdge createTemplateEdge(
            GraphTemplate template, TemplateNode source, TemplateNode target, String condition) {
        TemplateEdge edge = new TemplateEdge();
        edge.setGraphTemplateId(template.getId());
        edge.setSourceNodeId(source.getId());
        edge.setTargetNodeId(target.getId());
        edge.setCondition(condition);
        return templateEdgeRepo.save(edge);
    }

    private NodeExecution createAwaitingHumanExecution(WorkflowRun run, TemplateNode node) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(node.getId());
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        return nodeExecutionRepo.save(exec);
    }

    /** Finds the snapshot node with the given {@code template_node_id} and returns its {@code decision_options}. */
    private List<String> decisionOptionsFromRunDetail(JsonNode runDetail, UUID templateNodeId) {
        for (JsonNode node : runDetail.get("graphSnapshot").get("nodes")) {
            if (node.get("template_node_id").asText().equals(templateNodeId.toString())) {
                List<String> options = new ArrayList<>();
                node.get("decision_options").forEach(o -> options.add(o.asText()));
                return options;
            }
        }
        throw new AssertionError("No snapshot node found for template_node_id " + templateNodeId);
    }

    /** Finds the pending-gates entry for the given node execution and returns its {@code decisionOptions}. */
    private List<String> decisionOptionsFromPendingGates(JsonNode pendingGates, UUID nodeExecutionId) {
        for (JsonNode entry : pendingGates.get("content")) {
            if (entry.get("nodeExecutionId").asText().equals(nodeExecutionId.toString())) {
                List<String> options = new ArrayList<>();
                entry.get("decisionOptions").forEach(o -> options.add(o.asText()));
                return options;
            }
        }
        throw new AssertionError("No pending-gate entry found for node execution " + nodeExecutionId);
    }

    @Test
    void getRun_decisionOptionsMatchPendingGatesForPlainEdgeGate() throws Exception {
        GraphTemplate template = createTemplate("Plain Edge Gate Template", "plain-edge-gate-template");
        NodeDefinition nodeDef = createHumanGateNodeDefinition("plain-edge-gate");

        TemplateNode gate = createTemplateNode(template, nodeDef, "Review", "{}", true);
        TemplateNode approvedNext = createTemplateNode(template, nodeDef, "After Approve", "{}", false);
        TemplateNode rejectedNext = createTemplateNode(template, nodeDef, "After Reject", "{}", false);
        createTemplateEdge(template, gate, approvedNext, "approved");
        createTemplateEdge(template, gate, rejectedNext, "rejected");

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("Plain Edge Gate Run");
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run = runRepo.save(run);

        NodeExecution exec = createAwaitingHumanExecution(run, gate);

        MvcResult runResult = mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult pendingGatesResult = mockMvc.perform(get("/api/v1/pending-gates"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode runDetail = objectMapper.readTree(runResult.getResponse().getContentAsString());
        JsonNode pendingGates =
                objectMapper.readTree(pendingGatesResult.getResponse().getContentAsString());

        List<String> fromRunDetail = decisionOptionsFromRunDetail(runDetail, gate.getId());
        List<String> fromPendingGates = decisionOptionsFromPendingGates(pendingGates, exec.getId());

        assertEquals(List.of("approved", "rejected"), fromRunDetail);
        assertEquals(fromPendingGates, fromRunDetail);
    }

    @Test
    void getRun_decisionOptionsMatchPendingGatesForTerminalDecisionGate() throws Exception {
        GraphTemplate template = createTemplate("Terminal Decision Gate Template", "terminal-decision-gate-template");
        NodeDefinition nodeDef = createHumanGateNodeDefinition("terminal-decision-gate");

        // Mirrors the Roadmap Provisioner's human gate: "approved" ends the run via
        // terminal_decisions with no corresponding graph edge, only "rejected" is a real edge.
        TemplateNode gate = createTemplateNode(
                template, nodeDef, "Roadmap Human Gate", "{\"terminal_decisions\":[\"approved\"]}", true);
        TemplateNode rejectedNext = createTemplateNode(template, nodeDef, "After Reject", "{}", false);
        createTemplateEdge(template, gate, rejectedNext, "rejected");

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("Terminal Decision Gate Run");
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run = runRepo.save(run);

        NodeExecution exec = createAwaitingHumanExecution(run, gate);

        MvcResult runResult = mockMvc.perform(get("/api/v1/runs/" + run.getId()))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult pendingGatesResult = mockMvc.perform(get("/api/v1/pending-gates"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode runDetail = objectMapper.readTree(runResult.getResponse().getContentAsString());
        JsonNode pendingGates =
                objectMapper.readTree(pendingGatesResult.getResponse().getContentAsString());

        List<String> fromRunDetail = decisionOptionsFromRunDetail(runDetail, gate.getId());
        List<String> fromPendingGates = decisionOptionsFromPendingGates(pendingGates, exec.getId());

        // This is the exact shape that was silently broken on the run-detail side before this
        // fix (edge-less "approved", only "rejected" as a real edge) while already working on
        // the pending-gates side.
        assertEquals(List.of("rejected", "approved"), fromRunDetail);
        assertEquals(fromPendingGates, fromRunDetail);
    }
}
