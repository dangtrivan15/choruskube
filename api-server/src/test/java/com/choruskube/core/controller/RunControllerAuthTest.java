package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class RunControllerAuthTest extends BaseTest {

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

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private GraphTemplate template;
    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        template = new GraphTemplate();
        template.setName("Auth Test Template");
        template.setGraphId("auth-test-template");
        template.setVersion(1);
        template.setInputSchema("[]");
        template = graphTemplateRepo.save(template);

        NodeDefinition nd = new NodeDefinition();
        nd.setName("auth-test-node");
        nd.setExecutorType(ExecutorType.ai);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        nd = nodeDefRepo.save(nd);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel("auth-test-node");
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(true);
        templateNodeRepo.save(tn);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("Auth Run");
        run = runRepo.save(run);
    }

    @Test
    void listRuns_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/runs")).andExpect(status().isOk());
    }

    @Test
    void getRun_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/runs/" + run.getId())).andExpect(status().isOk());
    }

    @Test
    void startRun_returnsCreated() throws Exception {
        WorkflowStub stub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.eq("DAGExecutorWorkflow"), ArgumentMatchers.any()))
                .thenReturn(stub);

        mockMvc.perform(post("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("graphTemplateId", template.getId().toString(), "inputs", Map.of()))))
                .andExpect(status().isCreated());
    }

    @Test
    void getReviewHistory_returnsOk() throws Exception {
        mockMvc.perform(get("/api/v1/runs/" + run.getId() + "/review-history")).andExpect(status().isOk());
    }
}
