package com.choruskube.core.service;

/**
 * Thin abstraction in front of the repository calls used by
 * {@link PublicMetricsService}. The split exists for test ergonomics:
 * {@code PublicMetricsCacheTest} mocks this interface to avoid spying on
 * a JPA proxy. There is exactly one production implementation
 * ({@link MetricsAggregatorImpl}) and one consumer ({@link PublicMetricsService}).
 *
 * <p>One indirection purely for test isolation.
 */
public interface MetricsAggregator {

    AggregateMetrics aggregate();

    /**
     * Pre-DTO container — the {@link PublicMetricsService} stamps timestamps and
     * cache TTL onto these raw values to build the public response.
     *
     * <p>{@code successRate} and {@code medianRunSeconds} are nullable; see
     * {@link com.choruskube.core.dto.LandingMetricsResponse} for the contract.
     */
    record AggregateMetrics(long totalRuns, Double successRate, long reposOrchestrated, Long medianRunSeconds) {}
}
