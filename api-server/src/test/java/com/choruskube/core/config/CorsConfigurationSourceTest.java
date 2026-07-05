package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Bean-level test for the CORS configuration that the public landing-page
 * endpoint inherits.
 *
 * <p><strong>Why a bean test, not a mockMvc preflight:</strong> the test profile
 * runs with {@code auth.enabled=false}, in which case
 * {@code SecurityConfig#securityFilterChain} takes the dev-mode branch which
 * does <em>not</em> call {@code .cors(...)}. A mockMvc preflight in the default
 * test profile would therefore never see CORS headers. This test exercises the
 * one thing the CORS config change actually controls: the property splitter
 * inside {@code corsConfigurationSource()}.
 */
@TestPropertySource(properties = "cors.allowed-origins=https://choruskube.com,http://localhost:13000")
class CorsConfigurationSourceTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @Test
    void allowsLandingOriginAndDevOrigin() {
        var req = new MockHttpServletRequest("OPTIONS", "/api/public/v1/landing-metrics");
        var config = corsConfigurationSource.getCorsConfiguration(req);
        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).contains("https://choruskube.com", "http://localhost:13000");
        // The endpoint inherits the existing bean's allow-credentials flag (used by the
        // authenticated app, harmless for the anonymous public endpoint).
        assertThat(config.getAllowCredentials()).isTrue();
    }
}
