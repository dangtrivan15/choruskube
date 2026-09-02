package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.config.WorkerAuthFilter;
import com.choruskube.core.dto.CreateWorkloadResponse;
import com.choruskube.core.dto.WorkloadLogsResponse;
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
    void createAuthorizesTheRunThenDelegates() throws Exception {
        when(workloadService.createWorkload(eq(runId), eq(nodeExecId), any()))
                .thenReturn(new CreateWorkloadResponse("agent-1", "hash"));

        mvc.perform(post(path(""))
                        .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc")
                        .contentType("application/json")
                        .content("{\"templateNodeId\":null,\"configJson\":{}}"))
                .andExpect(status().isCreated());

        verify(authorizer).requireMayActOn("ckw_abc", runId);
    }

    @Test
    void deleteAndLogsAreRunScopedAndAuthorized() throws Exception {
        when(workloadService.getWorkloadLogs(runId, nodeExecId, 50)).thenReturn(new WorkloadLogsResponse("out"));

        mvc.perform(delete(path("")).requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc"))
                .andExpect(status().isNoContent());
        mvc.perform(get(path("/logs")).requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc"))
                .andExpect(status().isOk());

        verify(workloadService).cleanupWorkload(runId, nodeExecId);
        verify(workloadService).getWorkloadLogs(runId, nodeExecId, 50);
        verify(authorizer, Mockito.times(2)).requireMayActOn("ckw_abc", runId);
    }

    @Test
    void aRejectedCredentialNeverReachesTheService() throws Exception {
        doThrow(new ForbiddenException("Unknown or revoked worker credential"))
                .when(authorizer)
                .requireMayActOn(any(), any());

        mvc.perform(delete(path("")).requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_wrong"))
                .andExpect(status().isForbidden());

        verify(workloadService, never()).cleanupWorkload(any(), any());
    }

    /**
     * The filter guards /worker/** by raw URI prefix while Spring MVC routes on the decoded path,
     * so an encoded path reaches this controller with no attribute set. Reaching the service in
     * that state would be an unauthenticated workload call.
     */
    @Test
    void aMissingCredentialAttributeIsRejectedWithoutConsultingTheAuthorizer() throws Exception {
        mvc.perform(delete(path(""))).andExpect(status().isUnauthorized());

        verify(authorizer, never()).requireMayActOn(any(), any());
        verify(workloadService, never()).cleanupWorkload(any(), any());
    }
}
