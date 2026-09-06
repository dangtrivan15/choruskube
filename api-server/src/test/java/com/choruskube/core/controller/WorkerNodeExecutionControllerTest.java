package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.config.WorkerAuthFilter;
import com.choruskube.core.dto.NodeExecutionResponse;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.GlobalExceptionHandler;
import com.choruskube.core.executor.WorkloadNamespaceResolver;
import com.choruskube.core.service.InternalRunService;
import com.choruskube.core.service.WorkerAuthorizer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkerNodeExecutionControllerTest {

    private InternalRunService runService;
    private WorkerAuthorizer authorizer;
    private WorkloadNamespaceResolver namespaceResolver;
    private MockMvc mvc;

    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final String base = "/worker/runs/%s/node-executions/%s";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runService = Mockito.mock(InternalRunService.class);
        authorizer = Mockito.mock(WorkerAuthorizer.class);
        namespaceResolver = Mockito.mock(WorkloadNamespaceResolver.class);
        ObjectProvider<WorkerAuthorizer> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(authorizer);
        ObjectProvider<WorkloadNamespaceResolver> nsProvider = Mockito.mock(ObjectProvider.class);
        when(nsProvider.getIfAvailable(any())).thenReturn(namespaceResolver);
        mvc = MockMvcBuilders.standaloneSetup(
                        new WorkerNodeExecutionController(provider, nsProvider, "unused-token", runService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String path(String suffix) {
        return String.format(base, runId, nodeExecId) + suffix;
    }

    private NodeExecutionResponse response(String status, String podName) {
        return new NodeExecutionResponse(
                nodeExecId,
                UUID.randomUUID(),
                status,
                null,
                null,
                podName,
                1,
                Instant.now(),
                null,
                null,
                1,
                null,
                "Test Node",
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void getNodeExecutionAuthorizesTheRunThenReturnsStatusIdAndResolvedNamespace() throws Exception {
        when(runService.getNodeExecution(runId, nodeExecId)).thenReturn(response("completed", "agent-xyz"));
        when(namespaceResolver.resolve(runId)).thenReturn("org-ns-42");

        mvc.perform(get(path("")).requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(nodeExecId.toString()))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.namespace").value("org-ns-42"));

        verify(authorizer).requireMayActOn("ckw_abc", runId);
        verify(runService).getNodeExecution(runId, nodeExecId);
        verify(namespaceResolver).resolve(runId);
    }

    @Test
    void getNodeExecutionRejectsAMissingCredentialWithoutConsultingTheAuthorizer() throws Exception {
        mvc.perform(get(path(""))).andExpect(status().isUnauthorized());

        verify(authorizer, never()).requireMayActOn(any(), any());
        verify(runService, never()).getNodeExecution(any(), any());
    }

    @Test
    void updateStatusAuthorizesTheRunThenDelegatesAndReturnsUpdated() throws Exception {
        when(runService.updateNodeExecutionStatus(eq(runId), eq(nodeExecId), any()))
                .thenReturn(response("failed", "agent-xyz"));

        mvc.perform(put(path("/status"))
                        .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc")
                        .contentType("application/json")
                        .content("{\"status\":\"failed\",\"errorMessage\":\"timed out\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"));

        verify(authorizer).requireMayActOn("ckw_abc", runId);
        verify(runService).updateNodeExecutionStatus(eq(runId), eq(nodeExecId), any());
    }

    @Test
    void updateStatusRejectsAMissingCredentialWithoutConsultingTheAuthorizer() throws Exception {
        mvc.perform(put(path("/status")).contentType("application/json").content("{\"status\":\"failed\"}"))
                .andExpect(status().isUnauthorized());

        verify(authorizer, never()).requireMayActOn(any(), any());
        verify(runService, never()).updateNodeExecutionStatus(any(), any(), any());
    }

    @Test
    void writeExecutionLogAuthorizesTheRunThenDelegatesAndReturns201() throws Exception {
        mvc.perform(post(path("/logs"))
                        .requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_abc")
                        .contentType("application/json")
                        .content("{\"level\":\"info\",\"message\":\"callback processed\"}"))
                .andExpect(status().isCreated());

        verify(authorizer).requireMayActOn("ckw_abc", runId);
        verify(runService).writeExecutionLog(eq(runId), eq(nodeExecId), any());
    }

    @Test
    void writeExecutionLogRejectsAMissingCredentialWithoutConsultingTheAuthorizer() throws Exception {
        mvc.perform(post(path("/logs")).contentType("application/json").content("{\"level\":\"info\"}"))
                .andExpect(status().isUnauthorized());

        verify(authorizer, never()).requireMayActOn(any(), any());
        verify(runService, never()).writeExecutionLog(any(), any(), any());
    }

    @Test
    void aRejectedCredentialNeverReachesTheService() throws Exception {
        doThrow(new ForbiddenException("Unknown or revoked worker credential"))
                .when(authorizer)
                .requireMayActOn(any(), any());

        mvc.perform(get(path("")).requestAttr(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE, "ckw_wrong"))
                .andExpect(status().isForbidden());

        verify(runService, never()).getNodeExecution(any(), any());
    }
}
