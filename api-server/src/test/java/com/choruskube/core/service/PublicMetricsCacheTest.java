package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.CacheConfig;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies that {@link PublicMetricsService#getLandingMetrics} only delegates to the
 * underlying {@link MetricsAggregator} once per cache entry, regardless of how many
 * times the service is invoked.
 *
 * <p>The {@code MetricsAggregator} is a thin interface (one method, one impl) that
 * exists specifically so this test can mock it cleanly without spying on a JPA proxy.
 *
 * <p>{@code @TestPropertySource} sets the cache TTL to 1 hour so entries don't expire
 * mid-test. The cache is explicitly cleared in {@code @BeforeEach} since it is a JVM
 * singleton shared across tests.
 */
@TestPropertySource(properties = "cache.landing-metrics.ttl-seconds=3600")
class PublicMetricsCacheTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private MetricsAggregator aggregator;

    @Autowired
    private PublicMetricsService service;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void evictCache() {
        var cache = cacheManager.getCache(CacheConfig.LANDING_METRICS);
        if (cache != null) {
            cache.clear();
        }
    }

    @Test
    void aggregatorInvokedOnce_acrossThreeServiceCalls() {
        when(aggregator.aggregate()).thenReturn(new MetricsAggregator.AggregateMetrics(7L, 99.5, 3L, 120L));

        var first = service.getLandingMetrics();
        var second = service.getLandingMetrics();
        var third = service.getLandingMetrics();

        verify(aggregator, times(1)).aggregate();
        // Cached entries are object-identical: every call returns the same instance.
        assertThat(first).isSameAs(second).isSameAs(third);
        assertThat(first.totalRuns()).isEqualTo(7L);
        assertThat(first.successRate()).isEqualTo(99.5);
        assertThat(first.reposOrchestrated()).isEqualTo(3L);
        assertThat(first.medianRunSeconds()).isEqualTo(120L);
        assertThat(first.cacheTtlSeconds()).isEqualTo(3600L);
    }

    @Test
    void aggregatorInvokedAgainAfterEviction() {
        when(aggregator.aggregate())
                .thenReturn(new MetricsAggregator.AggregateMetrics(1L, null, 0L, null))
                .thenReturn(new MetricsAggregator.AggregateMetrics(2L, 50.0, 1L, 60L));

        var firstResp = service.getLandingMetrics();
        evictCache();
        var secondResp = service.getLandingMetrics();

        verify(aggregator, times(2)).aggregate();
        assertThat(firstResp.totalRuns()).isEqualTo(1L);
        assertThat(firstResp.successRate()).isNull();
        assertThat(firstResp.medianRunSeconds()).isNull();
        assertThat(secondResp.totalRuns()).isEqualTo(2L);
        assertThat(secondResp.medianRunSeconds()).isEqualTo(60L);
    }
}
