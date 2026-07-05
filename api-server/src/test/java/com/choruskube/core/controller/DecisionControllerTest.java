package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.service.InternalRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests for decision endpoints. Authentication is now handled by InternalAuthFilter;
 * these tests run without auth configured (filter passes through).
 */
@AutoConfigureMockMvc
public class DecisionControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InternalRunService service;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NODE_EXEC_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

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
}
