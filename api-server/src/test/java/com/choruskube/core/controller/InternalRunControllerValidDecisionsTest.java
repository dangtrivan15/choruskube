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
import com.choruskube.core.service.InternalRunService;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code RunScopeInterceptor} runs regardless of auth configuration, so {@code RUN_ID}/{@code
 * NODE_EXEC_ID} must be a real, seeded pair rather than made-up ids.
 */
@AutoConfigureMockMvc
@Transactional
public class InternalRunControllerValidDecisionsTest extends BaseTest {

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
    private InternalRunService service;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private UUID RUN_ID;
    private UUID NODE_EXEC_ID;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Valid Decisions Test Template");
        template.setGraphId("valid-decisions-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("valid-decisions-test-node");
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
        templateNode.setLabel("Valid Decisions Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run = runRepo.save(run);
        RUN_ID = run.getId();

        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(templateNode.getId());
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);
        NODE_EXEC_ID = exec.getId();
    }

    @Test
    void returnsValidDecisionsAsJsonArray() throws Exception {
        when(service.getValidDecisions(RUN_ID, NODE_EXEC_ID))
                .thenReturn(List.of("approved", "revised", "need_human_decision:review_conflict"));

        mockMvc.perform(get(
                        "/internal/runs/{runId}/node-executions/{nodeExecId}/valid-decisions", RUN_ID, NODE_EXEC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions[0]").value("approved"))
                .andExpect(jsonPath("$.decisions[1]").value("revised"))
                .andExpect(jsonPath("$.decisions[2]").value("need_human_decision:review_conflict"));
    }

    @Test
    void returnsEmptyArrayWhenNoConditionalEdges() throws Exception {
        when(service.getValidDecisions(RUN_ID, NODE_EXEC_ID)).thenReturn(List.of());

        mockMvc.perform(get(
                        "/internal/runs/{runId}/node-executions/{nodeExecId}/valid-decisions", RUN_ID, NODE_EXEC_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions").isArray())
                .andExpect(jsonPath("$.decisions.length()").value(0));
    }
}
