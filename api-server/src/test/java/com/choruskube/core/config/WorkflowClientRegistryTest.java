package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.junit.jupiter.api.Test;

class WorkflowClientRegistryTest {

    private static final String DEFAULT_NS = "choruskube";

    /**
     * Roughly seventy test classes mock the WorkflowClient bean so TemporalConfig does not dial a
     * real server. Routing the default namespace back to that same bean is what keeps them
     * working, so it is asserted rather than assumed.
     */
    @Test
    void clientFor_theConfiguredNamespace_isTheInjectedBean() {
        WorkflowClient defaultClient = mock(WorkflowClient.class);
        WorkflowClientRegistry registry =
                new WorkflowClientRegistry(mock(WorkflowServiceStubs.class), defaultClient, DEFAULT_NS);

        assertThat(registry.clientFor(DEFAULT_NS)).isSameAs(defaultClient);
    }

    /**
     * A run started before workflow_run.temporal_namespace existed ran in the configured
     * namespace by construction, so null is a fact about history, not a guess.
     */
    @Test
    void clientFor_null_isTheInjectedBean() {
        WorkflowClient defaultClient = mock(WorkflowClient.class);
        WorkflowClientRegistry registry =
                new WorkflowClientRegistry(mock(WorkflowServiceStubs.class), defaultClient, DEFAULT_NS);

        assertThat(registry.clientFor(null)).isSameAs(defaultClient);
    }

    @Test
    void clientFor_blank_isTheInjectedBean() {
        WorkflowClient defaultClient = mock(WorkflowClient.class);
        WorkflowClientRegistry registry =
                new WorkflowClientRegistry(mock(WorkflowServiceStubs.class), defaultClient, DEFAULT_NS);

        assertThat(registry.clientFor("   ")).isSameAs(defaultClient);
    }

    @Test
    void clientFor_anotherNamespace_isCachedNotRebuilt() {
        WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
        // WorkflowClient.newInstance reads stubs.getOptions().getMetricsScope(); plain build()
        // leaves that field null (only validateAndBuildWithDefaults() fills it), and an un-stubbed
        // mock's getOptions() is null outright, so either one NPEs before this test can assert.
        when(stubs.getOptions())
                .thenReturn(WorkflowServiceStubsOptions.newBuilder().validateAndBuildWithDefaults());
        WorkflowClientRegistry registry = new WorkflowClientRegistry(stubs, mock(WorkflowClient.class), DEFAULT_NS);

        WorkflowClient first = registry.clientFor("tenant-ns");

        assertThat(registry.clientFor("tenant-ns")).isSameAs(first);
    }

    @Test
    void clientFor_anotherNamespace_isNotTheDefaultClient() {
        WorkflowClient defaultClient = mock(WorkflowClient.class);
        WorkflowServiceStubs stubs = mock(WorkflowServiceStubs.class);
        when(stubs.getOptions())
                .thenReturn(WorkflowServiceStubsOptions.newBuilder().validateAndBuildWithDefaults());
        WorkflowClientRegistry registry = new WorkflowClientRegistry(stubs, defaultClient, DEFAULT_NS);

        assertThat(registry.clientFor("tenant-ns")).isNotSameAs(defaultClient);
    }
}
