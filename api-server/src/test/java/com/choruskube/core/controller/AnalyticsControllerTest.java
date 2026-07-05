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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class AnalyticsControllerTest extends BaseTest {

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

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Test
    void getOverview_emptyDatabase_returnsZeros() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(0))
                .andExpect(jsonPath("$.completedRuns").value(0))
                .andExpect(jsonPath("$.failedRuns").value(0))
                .andExpect(jsonPath("$.successRate").value(0.0));
    }

    @Test
    void getOverview_withRuns_returnsCorrectCounts() throws Exception {
        GraphTemplate template = createTemplate("test-tmpl");
        createRun(template, WorkflowRunStatus.completed);
        createRun(template, WorkflowRunStatus.completed);
        createRun(template, WorkflowRunStatus.failed);

        mockMvc.perform(get("/api/v1/analytics/overview?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(3))
                .andExpect(jsonPath("$.completedRuns").value(2))
                .andExpect(jsonPath("$.failedRuns").value(1));
    }

    @Test
    void getOverview_defaultPeriod_works() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(0));
    }

    @Test
    void getRunTrend_emptyDatabase_returnsEmptyPoints() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/runs?period=7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points").isEmpty());
    }

    @Test
    void getRunTrend_withRuns_returnsDailyPoints() throws Exception {
        GraphTemplate template = createTemplate("trend-tmpl");
        createRun(template, WorkflowRunStatus.completed);
        createRun(template, WorkflowRunStatus.failed);

        mockMvc.perform(get("/api/v1/analytics/runs?period=7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points[0].date").isString())
                .andExpect(jsonPath("$.points[0].total").isNumber());
    }

    @Test
    void getTemplateAnalytics_withRuns_returnsPerTemplate() throws Exception {
        GraphTemplate template = createTemplate("tmpl-analytics");
        createRun(template, WorkflowRunStatus.completed);

        mockMvc.perform(get("/api/v1/analytics/templates?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates").isArray())
                .andExpect(jsonPath("$.templates[0].templateName").value("tmpl-analytics"));
    }

    @Test
    void getNodeAnalytics_emptyDatabase_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/nodes?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.nodes").isEmpty());
    }

    @Test
    void getNodeAnalytics_withExecutions_returnsNodeStats() throws Exception {
        GraphTemplate template = createTemplate("node-tmpl");
        TemplateNode tn = createTemplateNode(template, "ai_draft");
        WorkflowRun run = createRun(template, WorkflowRunStatus.completed);
        createNodeExecution(run, tn, "ai_draft", NodeExecutionStatus.completed, 1);
        createNodeExecution(run, tn, "ai_draft", NodeExecutionStatus.failed, 2);

        mockMvc.perform(get("/api/v1/analytics/nodes?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.nodes[0].label").value("ai_draft"))
                .andExpect(jsonPath("$.nodes[0].executionCount").value(2));
    }

    @Test
    void getBottlenecks_emptyDatabase_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/bottlenecks?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bottlenecks").isArray())
                .andExpect(jsonPath("$.bottlenecks").isEmpty());
    }

    @Test
    void getBottlenecks_withCompletedNodes_returnsDurations() throws Exception {
        GraphTemplate template = createTemplate("bottleneck-tmpl");
        TemplateNode tn = createTemplateNode(template, "slow_node");
        WorkflowRun run = createRun(template, WorkflowRunStatus.completed);
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = start.plusSeconds(120);
        NodeExecution exec = createNodeExecution(run, tn, "slow_node", NodeExecutionStatus.completed, 1);
        exec.setStartedAt(start);
        exec.setCompletedAt(end);
        execRepo.save(exec);

        mockMvc.perform(get("/api/v1/analytics/bottlenecks?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bottlenecks").isArray())
                .andExpect(jsonPath("$.bottlenecks[0].label").value("slow_node"))
                .andExpect(jsonPath("$.bottlenecks[0].sampleSize").value(1));
    }

    // --- Helpers ---

    private GraphTemplate createTemplate(String name) {
        GraphTemplate template = new GraphTemplate();
        template.setName(name);
        template.setGraphId(name);
        template.setVersion(1);
        return templateRepo.save(template);
    }

    private TemplateNode createTemplateNode(GraphTemplate template, String label) {
        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName(label + "-def");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test-image:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nodeDef.getId());
        tn.setLabel(label);
        tn.setConfigOverrides("{}");
        tn.setEntrypoint(true);
        return templateNodeRepo.save(tn);
    }

    private WorkflowRun createRun(GraphTemplate template, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(status);
        run.setStartedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        if (status == WorkflowRunStatus.completed || status == WorkflowRunStatus.failed) {
            run.setCompletedAt(Instant.now());
        }
        return runRepo.save(run);
    }

    private NodeExecution createNodeExecution(
            WorkflowRun run, TemplateNode tn, String label, NodeExecutionStatus status, int iteration) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(tn.getId());
        exec.setStatus(status);
        exec.setLabel(label);
        exec.setGraphVersion(1);
        exec.setIteration(iteration);
        return execRepo.save(exec);
    }
}
