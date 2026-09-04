package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.config.WorkerAuthFilter;
import com.choruskube.core.dto.CompleteWorkloadRequest;
import com.choruskube.core.dto.PrepareWorkloadResponse;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.GlobalExceptionHandler;
import com.choruskube.core.service.WorkerAuthorizer;
import com.choruskube.core.service.WorkloadService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkerWorkloadControllerTest {

    private WorkloadService workloadService;
    private WorkerAuthorizer authorizer;
    private MockMvc mvc;

    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final String base = "/worker/runs/%s/node-executions/%s/workload";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workloadService = Mockito.mock(WorkloadService.class);
        authorizer = Mockito.mock(WorkerAuthorizer.class);
        ObjectProvider<WorkerAuthorizer> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(authorizer);
        mvc = MockMvcBuilders.standaloneSetup(new WorkerWorkloadController(provider, "unused-token", workloadService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String path(String suffix) {
        return String.format(base, runId, nodeExecId) + suffix;
    }

    @Test
    void prepareAuthorizesTheRunThenDelegates() throws Exception {
        var response = new PrepareWorkloadResponse(
                "agent:latest", false, "oauth-token", "http://api/github-token", null, null, "choruskube-agent");
        when(workloadService.prepareWorkload(eq(runId), eq(nodeExecId), any())).thenReturn(response);

        mvc.perform(post(path("/prepare"))
                        .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc")
                        .contentType("application/json")
                        .content("{\"templateNodeId\":null,\"configJson\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.image").value("agent:latest"))
                .andExpect(jsonPath("$.serviceAccount").value("choruskube-agent"));

        verify(authorizer).requireMayActOn("ckw_abc", runId);
        verify(workloadService).prepareWorkload(eq(runId), eq(nodeExecId), any());
    }

    @Test
    void completeAuthorizesTheRunThenDelegates() throws Exception {
        mvc.perform(post(path("/complete"))
                        .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc")
                        .contentType("application/json")
                        .content("{\"podName\":\"agent-abc12345\",\"jobSecretHash\":\"deadbeef\"}"))
                .andExpect(status().isOk());

        verify(authorizer).requireMayActOn("ckw_abc", runId);
        verify(workloadService)
                .completeWorkload(
                        eq(runId), eq(nodeExecId), eq(new CompleteWorkloadRequest("agent-abc12345", "deadbeef")));
    }

    @Test
    void aRejectedCredentialNeverReachesTheService() throws Exception {
        doThrow(new ForbiddenException("Unknown or revoked worker credential"))
                .when(authorizer)
                .requireMayActOn(any(), any());

        mvc.perform(post(path("/prepare"))
                        .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_wrong")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(workloadService, never()).prepareWorkload(any(), any(), any());
    }

    @Test
    void aMissingCredentialAttributeIsRejectedWithoutConsultingTheAuthorizer() throws Exception {
        mvc.perform(post(path("/prepare")).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        verify(authorizer, never()).requireMayActOn(any(), any());
        verify(workloadService, never()).prepareWorkload(any(), any(), any());
    }

    @Test
    void aBlankCredentialAttributeIsRejectedWithoutConsultingTheAuthorizer() throws Exception {
        mvc.perform(post(path("/prepare"))
                        .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "   ")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        verify(authorizer, never()).requireMayActOn(any(), any());
        verify(workloadService, never()).prepareWorkload(any(), any(), any());
    }

    @Test
    void prepareAndCompleteRejectAMissingCredentialWithoutConsultingTheAuthorizer() throws Exception {
        mvc.perform(post(path("/prepare")).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path("/complete")).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());

        verify(authorizer, never()).requireMayActOn(any(), any());
        verify(workloadService, never()).prepareWorkload(any(), any(), any());
        verify(workloadService, never()).completeWorkload(any(), any(), any());
    }
}
