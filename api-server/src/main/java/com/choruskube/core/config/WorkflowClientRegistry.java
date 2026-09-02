package com.choruskube.core.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out a {@link WorkflowClient} bound to a given Temporal namespace.
 *
 * <p>All of them share one {@link WorkflowServiceStubs}: the stubs own the gRPC channel and the
 * client is a namespace-bound facade over it, so this multiplies facades, not connections.
 */
public class WorkflowClientRegistry {

    private final WorkflowServiceStubs stubs;
    private final WorkflowClient defaultClient;
    private final String defaultNamespace;
    private final Map<String, WorkflowClient> byNamespace = new ConcurrentHashMap<>();

    public WorkflowClientRegistry(WorkflowServiceStubs stubs, WorkflowClient defaultClient, String defaultNamespace) {
        this.stubs = stubs;
        this.defaultClient = defaultClient;
        this.defaultNamespace = defaultNamespace;
    }

    /**
     * @param namespace a run's recorded namespace; null or blank for a run started before the
     *     column existed, which ran in the configured namespace by construction
     */
    public WorkflowClient clientFor(String namespace) {
        // Returning the injected bean rather than an equivalent new client keeps every test that
        // mocks WorkflowClient working for single-namespace deployments.
        if (namespace == null || namespace.isBlank() || namespace.equals(defaultNamespace)) {
            return defaultClient;
        }
        return byNamespace.computeIfAbsent(
                namespace,
                ns -> WorkflowClient.newInstance(
                        stubs,
                        WorkflowClientOptions.newBuilder().setNamespace(ns).build()));
    }
}
