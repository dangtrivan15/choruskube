package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.service.WorkerCredentialSources;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final String TOKEN = "ckf_self_hosted_secret";

    private static final String BODY =
            "{\"hostname\":\"oss-worker-1\",\"instanceId\":\"11111111-1111-1111-1111-111111111111\","
                    + "\"credentialSource\":\"control-plane\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkerCredentialSources credentialSources;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    /** This table is shared across the whole suite's single database, not reset between tests by Spring. */
    @BeforeEach
    void clearCredentialSourceTable() {
        jdbc.update("DELETE FROM single_fleet_credential_source");
    }

    private static String bodyWithCredentialSource(String instanceId, String credentialSource) {
        return "{\"hostname\":\"oss-worker-1\",\"instanceId\":\"" + instanceId + "\",\"credentialSource\":\""
                + credentialSource + "\"}";
    }

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

    @Test
    void register_local_returns200AndIsAnsweredAsHoldingOwnCredentials() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCredentialSource("11111111-1111-1111-1111-111111111111", "local")))
                .andExpect(status().isOk());

        assertThat(credentialSources.holdsOwnCredentials(TOKEN)).isTrue();
    }

    @Test
    void register_controlPlane_returns200AndIsAnsweredAsNotHoldingOwnCredentials() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCredentialSource("22222222-2222-2222-2222-222222222222", "control-plane")))
                .andExpect(status().isOk());

        assertThat(credentialSources.holdsOwnCredentials(TOKEN)).isFalse();
    }

    /** A Worker binary built before this field existed cannot register at all; there is no default. */
    @Test
    void register_missingCredentialSource_returns400AndLeavesAnyExistingRowUnchanged() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCredentialSource("33333333-3333-3333-3333-333333333333", "local")))
                .andExpect(status().isOk());

        String legacyBody = "{\"hostname\":\"oss-worker-1\",\"instanceId\":\"33333333-3333-3333-3333-333333333333\"}";
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyBody))
                .andExpect(status().isBadRequest());

        assertThat(credentialSources.holdsOwnCredentials(TOKEN)).isTrue();
    }

    @Test
    void register_unrecognizedCredentialSource_returns400AndLeavesAnyExistingRowUnchanged() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCredentialSource("44444444-4444-4444-4444-444444444444", "local")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCredentialSource("44444444-4444-4444-4444-444444444444", "bogus")))
                .andExpect(status().isBadRequest());

        // Seeded with local, not control-plane: control-plane is also the no-row answer, so only
        // local proves the refused declaration neither replaced nor cleared the record.
        assertThat(credentialSources.holdsOwnCredentials(TOKEN)).isTrue();
    }

    /** All Workers share one token on this server, so the latest declaration is the one recorded. */
    @Test
    void register_localThenControlPlane_leavesTheFinalStoredStateAsControlPlane() throws Exception {
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCredentialSource("55555555-5555-5555-5555-555555555555", "local")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/worker/register")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCredentialSource("66666666-6666-6666-6666-666666666666", "control-plane")))
                .andExpect(status().isOk());

        assertThat(credentialSources.holdsOwnCredentials(TOKEN)).isFalse();
    }
}
