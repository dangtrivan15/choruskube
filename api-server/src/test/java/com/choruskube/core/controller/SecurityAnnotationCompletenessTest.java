package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@AutoConfigureMockMvc
class SecurityAnnotationCompletenessTest extends BaseTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    // Share the same Spring context as other controller tests that mock Temporal
    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Test
    void allPublicApiEndpoints_havePreAuthorize() {
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            Set<String> patterns = mapping.getPatternValues();
            boolean isApiEndpoint = patterns.stream().anyMatch(p -> p.startsWith("/api/"));
            if (isApiEndpoint) {
                assertThat(method.getMethod().isAnnotationPresent(PreAuthorize.class))
                        .as(
                                "Method %s.%s (%s) must have @PreAuthorize",
                                method.getBeanType().getSimpleName(),
                                method.getMethod().getName(),
                                patterns)
                        .isTrue();
            }
        });
    }

    @Test
    void internalEndpoints_doNotHavePreAuthorize() {
        handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
            Set<String> patterns = mapping.getPatternValues();
            boolean isInternalEndpoint = patterns.stream().anyMatch(p -> p.startsWith("/internal/"));
            if (isInternalEndpoint) {
                assertThat(method.getMethod().isAnnotationPresent(PreAuthorize.class))
                        .as(
                                "Internal method %s.%s (%s) should NOT have @PreAuthorize",
                                method.getBeanType().getSimpleName(),
                                method.getMethod().getName(),
                                patterns)
                        .isFalse();
            }
        });
    }
}
