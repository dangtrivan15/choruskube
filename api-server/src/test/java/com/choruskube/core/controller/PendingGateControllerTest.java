package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
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
public class PendingGateControllerTest extends BaseTest {

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
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Test
    void getPendingGates_returnsEmptyWhenNoAwaiting() throws Exception {
        mockMvc.perform(get("/api/v1/pending-gates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void getPendingGateCount_returnsZeroWhenNoAwaiting() throws Exception {
        mockMvc.perform(get("/api/v1/pending-gates/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void getPendingGates_returnsAwaitingHumanGate() throws Exception {
        // Create template and node definition
        GraphTemplate template = new GraphTemplate();
        template.setName("Test Workflow");
        template.setGraphId("test-wf");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("human-review");
        nodeDef.setExecutorType(ExecutorType.human);
        nodeDef.setImage("placeholder");
        nodeDef.setPromptTemplate("");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef.setTimeoutSeconds(1800);
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nodeDef.getId());
        tn.setLabel("Review Code");
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(true);
        tn = templateNodeRepo.save(tn);

        // Create run — snapshot is built on-demand from template tables
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("My Test Run");
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run = runRepo.save(run);

        // Create awaiting_human node execution
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(tn.getId());
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        // Test the endpoint
        mockMvc.perform(get("/api/v1/pending-gates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].nodeExecutionId")
                        .value(exec.getId().toString()))
                .andExpect(jsonPath("$.content[0].runId").value(run.getId().toString()))
                .andExpect(jsonPath("$.content[0].runName").value("My Test Run"))
                .andExpect(jsonPath("$.content[0].nodeLabel").value("Review Code"))
                .andExpect(jsonPath("$.content[0].timeoutSeconds").value(1800))
                .andExpect(jsonPath("$.content[0].status").value("awaiting_human"));

        // Test count endpoint
        mockMvc.perform(get("/api/v1/pending-gates/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void getPendingGates_includesLiveChatGate() throws Exception {
        // Create template and node definition
        GraphTemplate template = new GraphTemplate();
        template.setName("Live Chat Workflow");
        template.setGraphId("chat-wf");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("human-chat");
        nodeDef.setExecutorType(ExecutorType.human);
        nodeDef.setImage("placeholder");
        nodeDef.setPromptTemplate("");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef.setTimeoutSeconds(1800);
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nodeDef.getId());
        tn.setLabel("Chat Review");
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(true);
        tn = templateNodeRepo.save(tn);

        // Create run
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setName("Chat Test Run");
        run.setStatus(WorkflowRunStatus.running);
        run = runRepo.save(run);

        // Create live_chat node execution
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(tn.getId());
        exec.setStatus(NodeExecutionStatus.live_chat);
        exec.setGraphVersion(1);
        exec = execRepo.save(exec);

        // Test the endpoint includes live_chat status
        mockMvc.perform(get("/api/v1/pending-gates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].nodeExecutionId")
                        .value(exec.getId().toString()))
                .andExpect(jsonPath("$.content[0].status").value("live_chat"))
                .andExpect(jsonPath("$.content[0].nodeLabel").value("Chat Review"));

        // Count should include live_chat
        mockMvc.perform(get("/api/v1/pending-gates/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }
}
