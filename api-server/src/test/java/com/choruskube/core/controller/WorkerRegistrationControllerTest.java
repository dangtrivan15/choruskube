package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole self-hosted registration path through the real filter chain: {@code WorkerAuthFilter}
 * for the bearer token, then the default {@code SingleFleetWorkerRegistrar} behind the controller's
 * {@code ObjectProvider}. Booting the container is the point — no bean here supplies a
 * {@code WorkerRegistrar}, so a 200 with the configured queue is what proves the default is
 * reached, and 401/403 prove the two rejection paths keep their distinct meanings.
 */
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "temporal.namespace=oss-namespace",
            "temporal.task-queue=oss-queue",
            // test-only: the shared secret a self-hosted deployment would inject as
            // WORKER_REGISTRATION_TOKEN
            "worker.registration.token=ckf_self_hosted_secret"
        })
class WorkerRegistrationControllerTest extends BaseTest {

    private static final String BODY =
            "{\"hostname\":\"oss-worker-1\",\"instanceId\":\"11111111-1111-1111-1111-111111111111\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Test
    void register_configuredToken_returnsTheOneFleetThisServerHas() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer ckf_self_hosted_secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporalNamespace").value("oss-namespace"))
                .andExpect(jsonPath("$.taskQueue").value("oss-queue"))
                .andExpect(jsonPath("$.workerId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.token").value(""))
                .andExpect(jsonPath("$.endpoint").value(""))
                .andExpect(jsonPath("$.expiresInSeconds").value(0));
    }

    @Test
    void register_noAuthorizationHeader_returns401WithTheMachineFacingErrorBody() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Missing fleet token\"}"));
    }

    @Test
    void register_nonBearerScheme_returns401() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Basic dXNlcjpwYXNz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void register_bearerPrefixWithNoToken_returns401() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A wrong token is a 403, not a 401: the filter proved a token was presented, and only the
     * registrar knows it names no Fleet. Collapsing the two would tell a misconfigured Worker to
     * retry without credentials.
     */
    @Test
    void register_unknownToken_returns403() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer ckf_definitelynotreal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    /**
     * {@code WorkerAuthFilter.shouldNotFilter} matches on {@code HttpServletRequest.getRequestURI()},
     * which is raw and percent-encoded, while Spring MVC dispatches on the decoded path — so
     * {@code /%77orker/register} slips past the filter's prefix check and still resolves to this
     * controller. Its own token check is what turns that into a 401 rather than a 500.
     */
    @Test
    void register_percentEncodedWorkerPathWithNoToken_returns401NotServerError() throws Exception {
        // post(String, Object...) treats its argument as a URI TEMPLATE and re-encodes it, turning
        // "%77" into "%2577". post(URI) preserves the already-encoded byte, as a real request line does.
        mockMvc.perform(post(URI.create("/%77orker/register"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }
}
