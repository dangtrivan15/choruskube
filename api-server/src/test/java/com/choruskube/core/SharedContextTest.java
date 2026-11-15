package com.choruskube.core;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * {@link BaseTest} with Temporal mocked and MockMvc configured: the one Spring context most core
 * tests share. Spring caches a context per distinct set of bean overrides and property sources, so
 * a subclass that adds a {@code @MockitoBean} or {@code @TestPropertySource} boots its own.
 */
@AutoConfigureMockMvc
public abstract class SharedContextTest extends BaseTest {

    @MockitoBean
    protected WorkflowClient workflowClient;

    @MockitoBean
    protected WorkflowServiceStubs workflowServiceStubs;
}
