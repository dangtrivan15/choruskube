package com.choruskube.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs every {@code @Scheduled} reconciler, so it must stay on outside tests. The test profile turns it off:
 * each cached test context would otherwise tick its reconcilers against the shared database mid-test.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "choruskube.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
