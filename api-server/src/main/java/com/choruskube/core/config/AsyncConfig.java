package com.choruskube.core.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("provisioningExecutor")
    public Executor provisioningExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("org-provision-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for identity-provider cleanup side-effects of soft-deleted invitations (and, once
     * it lands, of soft-deleted orgs). Separated from {@code provisioningExecutor} so an IdP
     * outage can't starve K8s provisioning and vice versa.
     */
    @Bean("identityCleanupExecutor")
    public Executor identityCleanupExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("idp-cleanup-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for Temporal workflow-termination side-effects of soft-deleted
     * workflow_runs. Separated from the IdP and K8s pools so a Temporal outage can't starve
     * either, and vice versa. Per-control-plane isolation keeps cleanup latency predictable
     * during partial outages.
     */
    @Bean("workflowTerminationExecutor")
    public Executor workflowTerminationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("wf-terminate-");
        executor.initialize();
        return executor;
    }
}
