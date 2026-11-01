package com.choruskube.core.controller;

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
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.NodePlacementChecker;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises the {@code /placement-check} endpoint's JSON contract (field names {@code allowed}/
 * {@code reason}, exact wire shape a later Go activity decodes) with a real {@link
 * NodePlacementChecker} bound in the Spring context. The absent-implementation default is
 * deliberately NOT tested here: an {@code @MockitoBean} of this type makes {@code
 * Optional<NodePlacementChecker>} non-empty for every test in this class, so it cannot exercise
 * {@code Optional.empty()} — see {@link InternalRunControllerPlacementNoCheckerTest} for that
 * case instead.
 */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class InternalRunControllerPlacementTest extends BaseTest {

    private static final String orchestratorSecret = "test-orchestrator-secret";

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

    @MockitoBean
    private NodePlacementChecker checker;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private WorkflowRun run;
    private NodeExecution exec;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Placement Test Template");
        template.setGraphId("placement-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("placement-test-node");
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
        templateNode.setLabel("Placement Test Node");
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
        exec = execRepo.save(exec);
    }

    @Test
    void placementCheck_checkerDenies_returnsReason() throws Exception {
        when(checker.check(run.getId(), exec.getId()))
                .thenReturn(new NodePlacementChecker.PlacementDecision(false, "fleet offline"));

        mockMvc.perform(post("/internal/runs/{r}/node-executions/{n}/placement-check", run.getId(), exec.getId())
                        .header("Authorization", "Bearer " + orchestratorSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("fleet offline"));
    }

    @Test
    void placementCheck_unrelatedRunAndExecution_isNotFound() throws Exception {
        mockMvc.perform(post(
                                "/internal/runs/{r}/node-executions/{n}/placement-check",
                                UUID.randomUUID(),
                                UUID.randomUUID())
                        .header("Authorization", "Bearer " + orchestratorSecret))
                .andExpect(status().isNotFound());
    }
}
