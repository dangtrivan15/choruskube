package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.exception.BadRequestException;
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

/**
 * Tests for decision endpoints. Authentication is now handled by InternalAuthFilter;
 * these tests run without auth configured (filter passes through). {@code RunScopeInterceptor}
 * still runs regardless of auth, so {@code RUN_ID}/{@code NODE_EXEC_ID} must be a real, seeded
 * pair rather than made-up ids.
 */
@AutoConfigureMockMvc
@Transactional
public class DecisionControllerTest extends BaseTest {

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
        template.setName("Decision Test Template");
        template.setGraphId("decision-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("decision-test-node");
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
        templateNode.setLabel("Decision Test Node");
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
    void submitDecision_validDecision_returns200() throws Exception {
        when(service.submitDecision(RUN_ID, NODE_EXEC_ID, "approve")).thenReturn("approve");

        Map<String, String> body = Map.of("decision", "approve");

        mockMvc.perform(put("/internal/runs/" + RUN_ID + "/node-executions/" + NODE_EXEC_ID + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("approve"));
    }

    @Test
    void submitDecision_invalidDecision_returns400() throws Exception {
        when(service.submitDecision(RUN_ID, NODE_EXEC_ID, "bad-value"))
                .thenThrow(new BadRequestException("Invalid decision: 'bad-value'. Valid options: [approve, reject]"));

        Map<String, String> body = Map.of("decision", "bad-value");

        mockMvc.perform(put("/internal/runs/" + RUN_ID + "/node-executions/" + NODE_EXEC_ID + "/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid decision")));
    }

    @Test
    void getDecision_returnsDecision() throws Exception {
        when(service.getDecision(RUN_ID, NODE_EXEC_ID)).thenReturn("approve");

        mockMvc.perform(get("/internal/runs/" + RUN_ID + "/node-executions/" + NODE_EXEC_ID + "/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("approve"));
    }

    @Test
    void getDecision_returnsNullWhenNotSet() throws Exception {
        when(service.getDecision(RUN_ID, NODE_EXEC_ID)).thenReturn(null);

        mockMvc.perform(get("/internal/runs/" + RUN_ID + "/node-executions/" + NODE_EXEC_ID + "/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").doesNotExist());
    }

    @Test
    void withdrawDecision_returnsTheWithdrawnDecision() throws Exception {
        when(service.withdrawDecision(RUN_ID, NODE_EXEC_ID)).thenReturn("escalate");

        mockMvc.perform(delete("/internal/runs/" + RUN_ID + "/node-executions/" + NODE_EXEC_ID + "/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("escalate"));
    }

    @Test
    void withdrawDecision_withNothingSubmitted_returns200AndNoDecision() throws Exception {
        when(service.withdrawDecision(RUN_ID, NODE_EXEC_ID)).thenReturn(null);

        mockMvc.perform(delete("/internal/runs/" + RUN_ID + "/node-executions/" + NODE_EXEC_ID + "/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").doesNotExist());
    }
}
