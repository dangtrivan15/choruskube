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
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class NodeDefinitionControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Test
    void createNodeDefinition_returns201() throws Exception {
        var body = Map.of("name", "AI Drafter", "executorType", "ai", "promptTemplate", "Draft a spec");

        mockMvc.perform(post("/api/v1/node-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("AI Drafter"))
                .andExpect(jsonPath("$.executorType").value("ai"));
    }

    @Test
    void getNodeDefinition_returns200() throws Exception {
        NodeDefinition nd = createTestNodeDef();

        mockMvc.perform(get("/api/v1/node-definitions/" + nd.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("test-node"));
    }

    @Test
    void listNodeDefinitions_returnsAll() throws Exception {
        createTestNodeDef();

        mockMvc.perform(get("/api/v1/node-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void updateNodeDefinition_returns200() throws Exception {
        NodeDefinition nd = createTestNodeDef();
        var body = Map.of("name", "Updated Name", "executorType", "human");

        mockMvc.perform(put("/api/v1/node-definitions/" + nd.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"))
                .andExpect(jsonPath("$.executorType").value("human"));
    }

    @Test
    void deleteNodeDefinition_whenNotReferenced_returns204() throws Exception {
        NodeDefinition nd = createTestNodeDef();

        mockMvc.perform(delete("/api/v1/node-definitions/" + nd.getId())).andExpect(status().isNoContent());
    }

    @Test
    void deleteNodeDefinition_whenReferenced_returns409() throws Exception {
        NodeDefinition nd = createTestNodeDef();

        GraphTemplate gt = new GraphTemplate();
        gt.setName("Test Template");
        gt.setGraphId("test-template");
        gt.setVersion(1);
        gt = graphTemplateRepo.save(gt);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(gt.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel("Test Node");
        tn.setConfigOverrides("{}");
        templateNodeRepo.save(tn);

        mockMvc.perform(delete("/api/v1/node-definitions/" + nd.getId())).andExpect(status().isConflict());
    }

    @Test
    void createNodeDefinition_withValidTimeout_returns201() throws Exception {
        var body = Map.of("name", "AI Drafter", "executorType", "ai", "timeoutSeconds", 600);

        mockMvc.perform(post("/api/v1/node-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeoutSeconds").value(600));
    }

    @Test
    void createNodeDefinition_withTooLowTimeout_returns400() throws Exception {
        var body = Map.of("name", "AI Drafter", "executorType", "ai", "timeoutSeconds", 30);

        mockMvc.perform(post("/api/v1/node-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNodeDefinition_withTooHighTimeout_returns400() throws Exception {
        var body = Map.of("name", "AI Drafter", "executorType", "ai", "timeoutSeconds", 100000);

        mockMvc.perform(post("/api/v1/node-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createNodeDefinition_withNullTimeout_usesDefault() throws Exception {
        var body = Map.of("name", "AI Drafter", "executorType", "ai");

        mockMvc.perform(post("/api/v1/node-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.timeoutSeconds").value(1800));
    }

    private NodeDefinition createTestNodeDef() {
        NodeDefinition nd = new NodeDefinition();
        nd.setName("test-node");
        nd.setExecutorType(ExecutorType.ai);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        return nodeDefRepo.save(nd);
    }
}
