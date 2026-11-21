package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.credential.TemporalBearerToken;
import com.choruskube.core.credential.TemporalTokenSupplier;
import com.choruskube.core.service.RunPlacement;
import com.choruskube.core.service.RunPlacementResolver;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /internal/placements} with a control-plane token source wired in — the companion to
 * {@link InternalPlacementControllerTest}'s no-source cases.
 */
@Transactional
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(
        properties = {
            "internal.auth.mode=enforce",
            // SHA-256 hash of "test-orchestrator-secret", same value InternalAuthFilterTest uses.
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d"
        })
class InternalPlacementTokenSourceTest extends SharedContextTest {

    private static final String ORCHESTRATOR_SECRET = "test-orchestrator-secret";
    private static final String DISTINCTIVE_TOKEN_PREFIX = "distinctive-bearer-token-xyz-";
    private static final String FAILING_NAMESPACE = "bad-ns";

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class TokenSourceConfig {
        @Bean
        TemporalTokenSupplier temporalTokenSupplier() {
            return ns -> {
                if (ns.equals(FAILING_NAMESPACE)) {
                    throw new IOException("token source refused");
                }
                return Optional.of(new TemporalBearerToken(
                        DISTINCTIVE_TOKEN_PREFIX + ns, Instant.now().plusSeconds(600)));
            };
        }

        /** Adds a second, always-failing namespace beside the configured one. */
        @Bean
        RunPlacementResolver runPlacementResolver() {
            return new RunPlacementResolver() {
                @Override
                public RunPlacement placeFor(UUID runId) {
                    throw new UnsupportedOperationException("not exercised by this test");
                }

                @Override
                public Set<String> namespaces() {
                    return Set.of(FAILING_NAMESPACE);
                }
            };
        }
    }

    @Test
    void withOrchestratorSecret_eachNamespaceCarriesABearerTokenAndExpiry() throws Exception {
        mockMvc.perform(get("/internal/placements").header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials.choruskube.state").value("bearer"))
                .andExpect(jsonPath("$.credentials.choruskube.token").value(DISTINCTIVE_TOKEN_PREFIX + "choruskube"))
                .andExpect(jsonPath("$.credentials.choruskube.expiresAt").exists());
    }

    @Test
    void supplierThrowingForOneNamespace_marksOnlyThatEntryUnavailable() throws Exception {
        mockMvc.perform(get("/internal/placements").header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentials." + FAILING_NAMESPACE + ".state")
                        .value("unavailable"))
                .andExpect(jsonPath("$.credentials." + FAILING_NAMESPACE + ".token")
                        .doesNotExist())
                .andExpect(jsonPath("$.credentials.choruskube.state").value("bearer"));
    }

    @Test
    void agentPerExecutionSecret_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/internal/placements").header("Authorization", "Bearer some-agent-job-secret"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noCapturedLogLineContainsTheTokenOrTheResponseBody(CapturedOutput output) throws Exception {
        String body = mockMvc.perform(
                        get("/internal/placements").header("Authorization", "Bearer " + ORCHESTRATOR_SECRET))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains(DISTINCTIVE_TOKEN_PREFIX); // the response itself does carry it
        assertThat(output.getOut()).doesNotContain(DISTINCTIVE_TOKEN_PREFIX);
        assertThat(output.getErr()).doesNotContain(DISTINCTIVE_TOKEN_PREFIX);
        assertThat(output.getOut()).doesNotContain(body);
        assertThat(output.getErr()).doesNotContain(body);
    }
}
