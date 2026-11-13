package com.choruskube.core.controller;

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.credential.GitHubTokenScope;
import com.choruskube.core.credential.GitHubTokenScopeResolver;
import com.choruskube.core.exception.NotFoundException;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Worker-facing GitHub token-scope route through the real filter chain: {@code
 * WorkerAuthFilter} for the bearer token, then the default single-Fleet authorizer behind the
 * controller's {@code ObjectProvider}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "worker.registration.token=test-fleet")
class WorkerCredentialControllerTest extends BaseTest {

    private final UUID runId = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private GitHubTokenScopeResolver scopeResolver;

    /** Core holds no credential and has no broker route at all, even for a valid Worker. */
    @Test
    void brokerRoute_isNotServed() throws Exception {
        mockMvc.perform(get("/worker/runs/{r}/github-token", runId).header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isNotFound());
    }

    // -----------------------------------------------------------------------------------
    // GET /worker/runs/{runId}/github-token-scope
    // -----------------------------------------------------------------------------------

    @Test
    void scopeRoute_validCredential_returnsTheRunsScope() throws Exception {
        GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/api", "acme/web"));
        when(scopeResolver.forRun(runId)).thenReturn(scope);

        mockMvc.perform(get("/worker/runs/{r}/github-token-scope", runId).header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories", contains("acme/api", "acme/web")))
                .andExpect(jsonPath("$.permissions.contents").value("write"));
    }

    @Test
    void scopeRoute_missingCredential_isUnauthorized() throws Exception {
        mockMvc.perform(get("/worker/runs/{r}/github-token-scope", runId)).andExpect(status().isUnauthorized());

        verifyNoInteractions(scopeResolver);
    }

    @Test
    void scopeRoute_wrongCredential_isRejected() throws Exception {
        mockMvc.perform(get("/worker/runs/{r}/github-token-scope", runId).header("Authorization", "Bearer nope"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(scopeResolver);
    }

    @Test
    void scopeRoute_percentEncodedPathWithNoCredential_isUnauthorized() throws Exception {
        mockMvc.perform(get(URI.create("/%77orker/runs/" + runId + "/github-token-scope")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(scopeResolver);
    }

    @Test
    void scopeRoute_unknownRun_isNotFound() throws Exception {
        when(scopeResolver.forRun(runId)).thenThrow(new NotFoundException("not found"));

        mockMvc.perform(get("/worker/runs/{r}/github-token-scope", runId).header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isNotFound());
    }
}
