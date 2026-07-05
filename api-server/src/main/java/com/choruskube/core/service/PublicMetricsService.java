package com.choruskube.core.service;

import com.choruskube.core.config.CacheConfig;
import com.choruskube.core.dto.LandingMetricsResponse;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cached aggregator for the public landing-page metrics endpoint.
 *
 * <p>The {@code @Cacheable} annotation caches the computed
 * {@link LandingMetricsResponse} in the {@link CacheConfig#LANDING_METRICS}
 * Caffeine cache; eviction is purely TTL-based (default 24h).
 */
@Service
public class PublicMetricsService {

    private final MetricsAggregator aggregator;
    private final long ttlSeconds;

    public PublicMetricsService(
            MetricsAggregator aggregator, @Value("${cache.landing-metrics.ttl-seconds:86400}") long ttlSeconds) {
        this.aggregator = aggregator;
        this.ttlSeconds = ttlSeconds;
    }

    @Cacheable(CacheConfig.LANDING_METRICS)
    public LandingMetricsResponse getLandingMetrics() {
        MetricsAggregator.AggregateMetrics m = aggregator.aggregate();
        return new LandingMetricsResponse(
                m.totalRuns(), m.successRate(), m.reposOrchestrated(), m.medianRunSeconds(), Instant.now(), ttlSeconds);
    }
}
