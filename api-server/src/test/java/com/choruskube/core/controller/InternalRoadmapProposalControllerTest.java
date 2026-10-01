package com.choruskube.core.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.InternalAuthFilter;
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
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
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
 * Auth-scoped coverage for the roadmap-proposal validate route and the write-refusal/caller-in-run
 * guard on the six agent-facing roadmap write routes, under an enforced job token — the same
 * split-context pattern as {@link InternalRunControllerPullRequestsAuthTest}.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class InternalRoadmapProposalControllerTest extends BaseTest {

    private static final String JOB_SECRET = "test-roadmap-proposal-job-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

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

    private static final String VALID_DOC = """
            {"epics":[{"title":"E","description":"d","motivation":"m","priority":"High",
              "stories":[{"title":"S","description":"d","tasks":[{"title":"T","description":"d"}]}]}]}
            """;

    private static final String INVALID_DOC = """
            {"epics":[{"description":"d","motivation":"m","stories":[]}]}
            """;

    @BeforeEach
    void setUp() {}

    private NodeDefinition makeNodeDef(String name, ExecutorType type) {
        NodeDefinition nd = new NodeDefinition();
        nd.setName(name);
        nd.setExecutorType(type);
        nd.setImage("test:latest");
        nd.setPromptTemplate(type == ExecutorType.ai ? "test" : null);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        return nodeDefRepo.save(nd);
    }

    private TemplateNode makeNode(
            GraphTemplate template,
            NodeDefinition nd,
            String label,
            boolean entrypoint,
            String configOverrides,
            String requiredInputArtifacts) {
        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel(label);
        tn.setEntrypoint(entrypoint);
        tn.setConfigOverrides(configOverrides);
        tn.setRequiredInputArtifacts(requiredInputArtifacts);
        return templateNodeRepo.save(tn);
    }

    private GraphTemplate makeTemplate(String graphId) {
        GraphTemplate t = new GraphTemplate();
        t.setGraphId(graphId);
        t.setVersion(1);
        t.setName(graphId);
        t.setInputSchema("[]");
        return templateRepo.save(t);
    }

    /** A run + exec pair, authenticated with {@code secret}, on the given template node. */
    private NodeExecution makeExec(WorkflowRun run, TemplateNode node, String secret) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(node.getId());
        exec.setGraphVersion(1);
        exec.setJobSecretHash(InternalAuthFilter.sha256Hex(secret));
        return execRepo.save(exec);
    }

    private WorkflowRun makeRun(GraphTemplate template) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        return runRepo.save(run);
    }

    // -----------------------------------------------------------------------
    // Validate route
    // -----------------------------------------------------------------------

    @Test
    void validate_validDocument_returns200WithCounts() throws Exception {
        GraphTemplate template = makeTemplate("roadmap-proposal-test-gated");
        NodeDefinition analyzerDef = makeNodeDef("analyzer", ExecutorType.ai);
        NodeDefinition gateDef = makeNodeDef("gate", ExecutorType.human);
        TemplateNode analyzer = makeNode(template, analyzerDef, "analyzer", true, "{}", null);
        makeNode(
                template,
                gateDef,
                "gate",
                false,
                "{\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_candidates\"}",
                "[{\"template_node_label\":\"analyzer\",\"artifacts\":[{\"name\":\"roadmap_candidates.json\"}]}]");
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, analyzer, JOB_SECRET);

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/roadmap-proposal/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DOC)
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("roadmap_candidates"))
                .andExpect(jsonPath("$.gateLabel").value("gate"))
                .andExpect(jsonPath("$.newEpics").value(1))
                .andExpect(jsonPath("$.newStories").value(1))
                .andExpect(jsonPath("$.newTasks").value(1));
    }

    @Test
    void validate_invalidDocument_returns400WithErrorsArray() throws Exception {
        GraphTemplate template = makeTemplate("roadmap-proposal-test-gated-invalid");
        NodeDefinition analyzerDef = makeNodeDef("analyzer", ExecutorType.ai);
        NodeDefinition gateDef = makeNodeDef("gate", ExecutorType.human);
        TemplateNode analyzer = makeNode(template, analyzerDef, "analyzer", true, "{}", null);
        makeNode(
                template,
                gateDef,
                "gate",
                false,
                "{\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_candidates\"}",
                "[{\"template_node_label\":\"analyzer\",\"artifacts\":[{\"name\":\"roadmap_candidates.json\"}]}]");
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, analyzer, JOB_SECRET);

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/roadmap-proposal/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_DOC)
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors", hasItem("epics[0]: a new epic needs at least one story")))
                .andExpect(jsonPath(
                        "$.errors", hasItem("epics[0].titleProvidedOrExisting: title is required for a new item")));
    }

    @Test
    void validate_nonUuidExistingId_returns400NamingTheField() throws Exception {
        GraphTemplate template = makeTemplate("roadmap-proposal-test-gated-type-error");
        NodeDefinition analyzerDef = makeNodeDef("analyzer", ExecutorType.ai);
        NodeDefinition gateDef = makeNodeDef("gate", ExecutorType.human);
        TemplateNode analyzer = makeNode(template, analyzerDef, "analyzer", true, "{}", null);
        makeNode(
                template,
                gateDef,
                "gate",
                false,
                "{\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_candidates\"}",
                "[{\"template_node_label\":\"analyzer\",\"artifacts\":[{\"name\":\"roadmap_candidates.json\"}]}]");
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, analyzer, JOB_SECRET);

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/roadmap-proposal/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"epics\":[{\"existingId\":\"<Epic id>\",\"stories\":[]}]}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0]", startsWith("epics[0].existingId: ")));
    }

    @Test
    void validate_gatelessTemplate_returns409() throws Exception {
        GraphTemplate template = makeTemplate("roadmap-proposal-test-gateless");
        NodeDefinition def = makeNodeDef("solo", ExecutorType.ai);
        TemplateNode node = makeNode(template, def, "solo", true, "{}", null);
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, node, JOB_SECRET);

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/roadmap-proposal/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DOC)
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isConflict());
    }

    @Test
    void validate_callerFromAnotherRun_returns404() throws Exception {
        // The execution's own valid JOB_SECRET, paired with a *different* run's id in the path:
        // InternalAuthFilter only checks the nodeExecId segment, so this is the only way to
        // exercise the service-layer requireCallerInRun cross-check.
        GraphTemplate template = makeTemplate("roadmap-proposal-test-cross-run");
        NodeDefinition def = makeNodeDef("solo", ExecutorType.ai);
        TemplateNode node = makeNode(template, def, "solo", true, "{}", null);
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, node, JOB_SECRET);

        WorkflowRun otherRun = makeRun(template);

        mockMvc.perform(post("/internal/runs/" + otherRun.getId() + "/node-executions/" + exec.getId()
                                + "/roadmap-proposal/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DOC)
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isNotFound());
    }

    @Test
    void validate_wrongJobToken_returns401() throws Exception {
        GraphTemplate template = makeTemplate("roadmap-proposal-test-401");
        NodeDefinition def = makeNodeDef("solo", ExecutorType.ai);
        TemplateNode node = makeNode(template, def, "solo", true, "{}", null);
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, node, JOB_SECRET);

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/roadmap-proposal/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_DOC)
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // Write refusal on the six write routes, against the real seeded templates
    // -----------------------------------------------------------------------

    @Test
    void writeRoutes_refusedOnFeatureDevV43Run() throws Exception {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        TemplateNode implement = templateNodeRepo.findByGraphTemplateId(v43.getId()).stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        WorkflowRun run = makeRun(v43);
        NodeExecution exec = makeExec(run, implement, JOB_SECRET);

        assertAllSixWriteRoutesRefused(run, exec);
    }

    @Test
    void writeRoutes_refusedOnRoadmapProvisionerRun() throws Exception {
        GraphTemplate provisioner = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.ROADMAP_PROVISIONER)
                .orElseThrow();
        TemplateNode analyzer = templateNodeRepo.findByGraphTemplateId(provisioner.getId()).stream()
                .filter(n -> "roadmap_analyzer".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        WorkflowRun run = makeRun(provisioner);
        NodeExecution exec = makeExec(run, analyzer, JOB_SECRET);

        assertAllSixWriteRoutesRefused(run, exec);
    }

    private void assertAllSixWriteRoutesRefused(WorkflowRun run, NodeExecution exec) throws Exception {
        String base = "/internal/runs/" + run.getId() + "/node-executions/" + exec.getId();

        mockMvc.perform(post(base + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"E\",\"description\":\"d\",\"priority\":\"medium\"}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("propose-roadmap")));

        mockMvc.perform(patch(base + "/feature-proposals/" + java.util.UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"E2\"}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(base + "/feature-proposals/" + java.util.UUID.randomUUID() + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"S\",\"description\":\"d\",\"priority\":\"medium\"}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(base + "/feature-proposals/" + java.util.UUID.randomUUID() + "/stories/"
                                + java.util.UUID.randomUUID() + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"description\":\"d\",\"priority\":\"medium\"}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(base + "/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockingItemType\":\"task\",\"blockingItemId\":\"" + java.util.UUID.randomUUID()
                                + "\",\"blockedItemType\":\"task\",\"blockedItemId\":\""
                                + java.util.UUID.randomUUID() + "\"}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(base + "/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"M\"}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeRoute_allowedOnGatelessTemplate() throws Exception {
        GraphTemplate template = makeTemplate("roadmap-proposal-test-gateless-write");
        NodeDefinition def = makeNodeDef("solo", ExecutorType.ai);
        TemplateNode node = makeNode(template, def, "solo", true, "{}", null);
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, node, JOB_SECRET);
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/test/roadmap-write-gateless");
        repo.setName("test/roadmap-write-gateless");
        repo.setSecrets("[]");
        repo = gitRepoRepo.save(repo);
        run.setInputs("{\"software_project_id\":\"" + repo.getId() + "\"}");
        runRepo.save(run);

        mockMvc.perform(post("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"E\",\"description\":\"d\",\"priority\":\"medium\"}")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isCreated());
    }

    // -----------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------

    @Test
    void listFeatureProposals_allowedForV43Run() throws Exception {
        GitRepo repo = new GitRepo();
        repo.setUrl("https://github.com/test/roadmap-list-allowed");
        repo.setName("test/roadmap-list-allowed");
        repo.setSecrets("[]");
        repo = gitRepoRepo.save(repo);

        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        TemplateNode implement = templateNodeRepo.findByGraphTemplateId(v43.getId()).stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        WorkflowRun run = makeRun(v43);
        run.setInputs("{\"software_project_id\":\"" + repo.getId() + "\"}");
        run = runRepo.save(run);
        NodeExecution exec = makeExec(run, implement, JOB_SECRET);

        mockMvc.perform(get("/internal/runs/" + run.getId() + "/node-executions/" + exec.getId() + "/feature-proposals")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listFeatureProposals_crossRunExecution_returns404() throws Exception {
        GraphTemplate template = makeTemplate("roadmap-proposal-test-list-cross-run");
        NodeDefinition def = makeNodeDef("solo", ExecutorType.ai);
        TemplateNode node = makeNode(template, def, "solo", true, "{}", null);
        WorkflowRun run = makeRun(template);
        NodeExecution exec = makeExec(run, node, JOB_SECRET);

        WorkflowRun otherRun = makeRun(template);

        mockMvc.perform(get("/internal/runs/" + otherRun.getId() + "/node-executions/" + exec.getId()
                                + "/feature-proposals")
                        .header("Authorization", "Bearer " + JOB_SECRET))
                .andExpect(status().isNotFound());
    }
}
