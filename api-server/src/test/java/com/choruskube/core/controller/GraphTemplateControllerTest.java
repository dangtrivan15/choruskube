package com.choruskube.core.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class GraphTemplateControllerTest extends BaseTest {

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

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Test
    void listTemplates_returnsAll() throws Exception {
        createTemplate("Template 1");
        createTemplate("Template 2");

        mockMvc.perform(get("/api/v1/graph-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void getTemplate_returnsTemplate() throws Exception {
        GraphTemplate gt = createTemplate("Test");

        mockMvc.perform(get("/api/v1/graph-templates/" + gt.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test"));
    }

    @Test
    void listNodes_returnsNodes() throws Exception {
        GraphTemplate gt = createTemplate("Test");
        NodeDefinition nd = createNodeDef();
        createTemplateNode(gt, nd, "Node A");

        mockMvc.perform(get("/api/v1/graph-templates/" + gt.getId() + "/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Node A"));
    }

    @Test
    void getNode_returnsNode() throws Exception {
        GraphTemplate gt = createTemplate("Test");
        NodeDefinition nd = createNodeDef();
        TemplateNode tn = createTemplateNode(gt, nd, "Node A");

        mockMvc.perform(get("/api/v1/graph-templates/" + gt.getId() + "/nodes/" + tn.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Node A"));
    }

    @Test
    void listEdges_returnsEdges() throws Exception {
        GraphTemplate gt = createTemplate("Test");

        mockMvc.perform(get("/api/v1/graph-templates/" + gt.getId() + "/edges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private GraphTemplate createTemplate(String name) {
        GraphTemplate gt = new GraphTemplate();
        gt.setName(name);
        gt.setGraphId(name.toLowerCase().replace(" ", "-"));
        gt.setVersion(1);
        return graphTemplateRepo.save(gt);
    }

    private NodeDefinition createNodeDef() {
        NodeDefinition nd = new NodeDefinition();
        nd.setName("test-node");
        nd.setExecutorType(ExecutorType.ai);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        return nodeDefRepo.save(nd);
    }

    private TemplateNode createTemplateNode(GraphTemplate gt, NodeDefinition nd, String label) {
        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(gt.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel(label);
        tn.setConfigOverrides("{}");
        return templateNodeRepo.save(tn);
    }
}
