package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Web-layer test for {@link PublicMetricsController}.
 *
 * <p>No auth header is sent — the endpoint is anonymous by design (allow-listed
 * in {@code SecurityConfig}). The response shape, HTTP status, and cache headers
 * are verified here. CORS is verified separately by {@code CorsConfigurationSourceTest}
 * (the dev-mode SecurityFilterChain doesn't wire {@code .cors(...)}, so a mockMvc
 * preflight cannot prove anything in the default test profile).
 */
@AutoConfigureMockMvc
@Transactional
class PublicMetricsControllerTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getLandingMetrics_anonymousAccess_returns200WithAllKeys() throws Exception {
        mockMvc.perform(get("/api/public/v1/landing-metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                // hasJsonPath() (not exists()) because successRate / medianRunSeconds may be
                // serialized as JSON null when the 90-day window contains no terminal runs.
                .andExpect(jsonPath("$.totalRuns").hasJsonPath())
                .andExpect(jsonPath("$.successRate").hasJsonPath())
                .andExpect(jsonPath("$.reposOrchestrated").hasJsonPath())
                .andExpect(jsonPath("$.medianRunSeconds").hasJsonPath())
                .andExpect(jsonPath("$.generatedAt").hasJsonPath())
                .andExpect(jsonPath("$.cacheTtlSeconds").value(86400));
    }

    @Test
    void getLandingMetrics_setsPublicCacheControlMaxAge24h() throws Exception {
        mockMvc.perform(get("/api/public/v1/landing-metrics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=86400")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")));
    }
}
