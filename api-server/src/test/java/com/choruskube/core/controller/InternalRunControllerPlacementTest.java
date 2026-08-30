package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.service.NodePlacementChecker;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce"
        })
class InternalRunControllerPlacementTest extends BaseTest {

    private static final String orchestratorSecret = "test-orchestrator-secret";
    private static final UUID runId = UUID.randomUUID();
    private static final UUID execId = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NodePlacementChecker checker;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Test
    void placementCheck_noChecker_allows() throws Exception {
        mockMvc.perform(post("/internal/runs/{r}/node-executions/{n}/placement-check", runId, execId)
                        .header("Authorization", "Bearer " + orchestratorSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void placementCheck_checkerDenies_returnsReason() throws Exception {
        when(checker.check(runId, execId))
                .thenReturn(new NodePlacementChecker.PlacementDecision(false, "fleet offline"));

        mockMvc.perform(post("/internal/runs/{r}/node-executions/{n}/placement-check", runId, execId)
                        .header("Authorization", "Bearer " + orchestratorSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("fleet offline"));
    }
}
