package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.service.InternalRunService;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
public class InternalRunControllerValidDecisionsTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InternalRunService service;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NODE_EXEC_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

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
