package com.choruskube.core.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.credential.GitHubCredentialResolver;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Worker-facing GitHub token route through the real filter chain: {@code WorkerAuthFilter} for
 * the bearer token, then the default single-Fleet authorizer behind the controller's {@code
 * ObjectProvider}. The resolver is mocked so no GitHub credential has to exist.
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
    private GitHubCredentialResolver resolver;

    @Test
    void validCredential_returnsTheRunsToken() throws Exception {
        when(resolver.getTokenForRun(runId)).thenReturn("ghs_abc");

        mockMvc.perform(get("/worker/runs/{r}/github-token", runId).header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("ghs_abc"));
    }

    @Test
    void missingCredential_isUnauthorized() throws Exception {
        mockMvc.perform(get("/worker/runs/{r}/github-token", runId)).andExpect(status().isUnauthorized());

        verifyNoInteractions(resolver);
    }

    @Test
    void wrongCredential_isRejected() throws Exception {
        mockMvc.perform(get("/worker/runs/{r}/github-token", runId).header("Authorization", "Bearer nope"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(resolver);
    }

    /** The filter prefix-matches the raw URI, so an encoded path skips it; the controller must still refuse. */
    @Test
    void percentEncodedWorkerPathWithNoCredential_isUnauthorized() throws Exception {
        mockMvc.perform(get(URI.create("/%77orker/runs/" + runId + "/github-token")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(resolver);
    }

    @Test
    void resolverFailure_isServiceUnavailable_withoutLeakingDetail() throws Exception {
        when(resolver.getTokenForRun(runId)).thenThrow(new IllegalStateException("No GitHub credential configured"));

        mockMvc.perform(get("/worker/runs/{r}/github-token", runId).header("Authorization", "Bearer test-fleet"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(content().string(not(containsString("No GitHub credential configured"))));
    }
}
