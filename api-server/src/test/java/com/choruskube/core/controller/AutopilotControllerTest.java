package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.service.RunEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class AutopilotControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AutopilotRepository autopilotRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void allowAllByDefault() {
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
    }

    @Test
    void get_withNoRow_returnsDisengaged_andInsertsNothing() throws Exception {
        mockMvc.perform(get("/api/v1/autopilot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engaged").value(false))
                .andExpect(jsonPath("$.maxParallel").value(1));

        assertThat(autopilotRepo.findAll()).isEmpty();
    }

    @Test
    void patch_setsMaxParallel() throws Exception {
        mockMvc.perform(patch("/api/v1/autopilot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("maxParallel", 5))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxParallel").value(5));
    }

    @Test
    void patch_withMaxParallelZero_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/autopilot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("maxParallel", 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void engage_thenGet_reflectsEngaged() throws Exception {
        mockMvc.perform(post("/api/v1/autopilot/engage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engaged").value(true));

        mockMvc.perform(get("/api/v1/autopilot"))
                .andExpect(jsonPath("$.engaged").value(true));
    }

    @Test
    void disengage_clearsEngaged() throws Exception {
        mockMvc.perform(post("/api/v1/autopilot/engage")).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/autopilot/disengage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engaged").value(false));

        mockMvc.perform(get("/api/v1/autopilot"))
                .andExpect(jsonPath("$.engaged").value(false));
    }

    // --- Authorization ladder ---

    @Test
    void get_belowCanReadPermission_returns403() throws Exception {
        when(orgSecurity.canRead()).thenReturn(false);

        mockMvc.perform(get("/api/v1/autopilot")).andExpect(status().isForbidden());
    }

    @Test
    void patch_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        mockMvc.perform(patch("/api/v1/autopilot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("maxParallel", 5))))
                .andExpect(status().isForbidden());
    }

    @Test
    void engage_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        mockMvc.perform(post("/api/v1/autopilot/engage")).andExpect(status().isForbidden());
    }

    @Test
    void disengage_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        mockMvc.perform(post("/api/v1/autopilot/disengage")).andExpect(status().isForbidden());
    }

    @Test
    void tick_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        mockMvc.perform(post("/api/v1/autopilot/tick")).andExpect(status().isForbidden());
    }
}
