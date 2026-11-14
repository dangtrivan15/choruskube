package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link WorkerAuthFilter} over {@link MockHttpServletRequest}, with no Spring
 * context — {@code RunScopeInterceptorTest} and {@code RunScopeRoutesTest} prove it end to end,
 * composed with {@link RunScopeInterceptor} and a real route table.
 */
class WorkerAuthFilterTest {

    private final WorkerAuthFilter filter = new WorkerAuthFilter();

    @Test
    void escapedWorkerPath_noBearer_isUnauthorized_andChainNeverContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/%77orker/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Missing fleet token\"}");
        verifyNoInteractions(chain);
    }

    @Test
    void escapedWorkerPath_withBearer_setsFleetTokenAttributeAndContinues() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("PUT", "/%77orker/runs/run-x/node-executions/exec-y/status");
        request.addHeader("Authorization", "Bearer some-fleet-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE)).isEqualTo("some-fleet-token");
        verify(chain).doFilter(request, response);
    }

    @Test
    void plainWorkerRegisterPath_noBearer_isUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/worker/register");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"Missing fleet token\"}");
        verifyNoInteractions(chain);
    }

    @Test
    void nonWorkerPath_passesThroughWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/epics");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE)).isNull();
        verify(chain).doFilter(request, response);
    }
}
