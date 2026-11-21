package com.choruskube.core.config;

import com.choruskube.core.credential.ControlPlaneTemporalTokens;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TemporalConfig {

    @Value("${temporal.address}")
    private String temporalAddress;

    @Value("${temporal.namespace}")
    private String temporalNamespace;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs(ControlPlaneTemporalTokens tokens) {
        return WorkflowServiceStubs.newServiceStubs(
                stubsOptions(temporalAddress, tokens).build());
    }

    /**
     * Shared by {@link #workflowServiceStubs} and the gRPC-boundary test, which calls {@code
     * setChannel(...)} on the returned builder in place of {@code setTarget}. {@code target} is
     * null only from that test: the SDK rejects a builder with both set.
     */
    static WorkflowServiceStubsOptions.Builder stubsOptions(String target, ControlPlaneTemporalTokens tokens) {
        WorkflowServiceStubsOptions.Builder builder = WorkflowServiceStubsOptions.newBuilder();
        if (target != null) {
            builder.setTarget(target);
        }
        // Only installed when a source is present: an idle interceptor still costs a namespace
        // lookup on every call, including the ones already covered by no credential at all.
        if (tokens.issuesTokens()) {
            builder.setGrpcClientInterceptors(List.of(new TemporalNamespaceCredentialInterceptor(tokens)));
        }
        return builder;
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        return WorkflowClient.newInstance(
                serviceStubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(temporalNamespace)
                        .build());
    }

    @Bean
    public WorkflowClientRegistry workflowClientRegistry(
            WorkflowServiceStubs serviceStubs, WorkflowClient workflowClient) {
        return new WorkflowClientRegistry(serviceStubs, workflowClient, temporalNamespace);
    }
}
