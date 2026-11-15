package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class AnalyticsControllerTest extends SharedContextTest {

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

    @Test
    void getOverview_returnsWellFormedCounts() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/overview?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").isNumber())
                .andExpect(jsonPath("$.completedRuns").isNumber())
                .andExpect(jsonPath("$.failedRuns").isNumber())
                .andExpect(jsonPath("$.successRate").isNumber());
    }

    @Test
    void getOverview_withRuns_countsExactlyTheRunsAdded() throws Exception {
        int total = overview("30d", "totalRuns");
        int completed = overview("30d", "completedRuns");
        int failed = overview("30d", "failedRuns");

        GraphTemplate template = createTemplate("test-tmpl-" + UUID.randomUUID());
        createRun(template, WorkflowRunStatus.completed);
        createRun(template, WorkflowRunStatus.completed);
        createRun(template, WorkflowRunStatus.failed);

        org.assertj.core.api.Assertions.assertThat(overview("30d", "totalRuns")).isEqualTo(total + 3);
        org.assertj.core.api.Assertions.assertThat(overview("30d", "completedRuns"))
                .isEqualTo(completed + 2);
        org.assertj.core.api.Assertions.assertThat(overview("30d", "failedRuns"))
                .isEqualTo(failed + 1);
    }

    @Test
    void getOverview_defaultPeriod_matchesThirtyDays() throws Exception {
        GraphTemplate template = createTemplate("default-period-" + UUID.randomUUID());
        createRun(template, WorkflowRunStatus.completed);

        mockMvc.perform(get("/api/v1/analytics/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(overview("30d", "totalRuns")));
    }

    @Test
    void getRunTrend_withRuns_returnsDailyPoints() throws Exception {
        GraphTemplate template = createTemplate("trend-tmpl-" + UUID.randomUUID());
        createRun(template, WorkflowRunStatus.completed);
        createRun(template, WorkflowRunStatus.failed);

        mockMvc.perform(get("/api/v1/analytics/runs?period=7d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points").isNotEmpty())
                .andExpect(jsonPath("$.points[0].date").isString())
                .andExpect(jsonPath("$.points[0].total").isNumber());
    }

    @Test
    void getTemplateAnalytics_withRuns_returnsPerTemplate() throws Exception {
        String name = "tmpl-analytics-" + UUID.randomUUID();
        GraphTemplate template = createTemplate(name);
        createRun(template, WorkflowRunStatus.completed);

        mockMvc.perform(get("/api/v1/analytics/templates?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates").isArray())
                .andExpect(jsonPath("$.templates[?(@.templateName=='" + name + "')]")
                        .isNotEmpty());
    }

    @Test
    void getNodeAnalytics_withExecutions_returnsNodeStats() throws Exception {
        String label = "ai_draft_" + UUID.randomUUID();
        GraphTemplate template = createTemplate("node-tmpl-" + UUID.randomUUID());
        TemplateNode tn = createTemplateNode(template, label);
        WorkflowRun run = createRun(template, WorkflowRunStatus.completed);
        createNodeExecution(run, tn, label, NodeExecutionStatus.completed, 1);
        createNodeExecution(run, tn, label, NodeExecutionStatus.failed, 2);

        mockMvc.perform(get("/api/v1/analytics/nodes?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray())
                .andExpect(jsonPath("$.nodes[?(@.label=='" + label + "')].executionCount")
                        .value(2));
    }

    @Test
    void getBottlenecks_withCompletedNodes_returnsDurations() throws Exception {
        String label = "slow_node_" + UUID.randomUUID();
        GraphTemplate template = createTemplate("bottleneck-tmpl-" + UUID.randomUUID());
        TemplateNode tn = createTemplateNode(template, label);
        WorkflowRun run = createRun(template, WorkflowRunStatus.completed);
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant end = start.plusSeconds(120);
        NodeExecution exec = createNodeExecution(run, tn, label, NodeExecutionStatus.completed, 1);
        exec.setStartedAt(start);
        exec.setCompletedAt(end);
        execRepo.save(exec);

        mockMvc.perform(get("/api/v1/analytics/bottlenecks?period=30d"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bottlenecks").isArray())
                .andExpect(jsonPath("$.bottlenecks[?(@.label=='" + label + "')].sampleSize")
                        .value(1));
    }

    // --- Helpers ---

    private int overview(String period, String field) throws Exception {
        String body = mockMvc.perform(get("/api/v1/analytics/overview?period=" + period))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$." + field);
    }

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
