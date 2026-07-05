package com.choruskube.core.dto;

import java.time.Instant;

/**
 * Public, anonymous response for {@code GET /api/public/v1/landing-metrics}.
 *
 * <p>{@code successRate} is nullable: when the 90-day window contains no terminal runs
 * we return {@code null} (rendered as {@code '—'} on the frontend) rather than {@code 0.0},
 * which would imply the platform fails 100% of the time.
 *
 * <p>{@code medianRunSeconds} is nullable for the same reason — when no qualifying
 * completed runs exist, the P50 is undefined.
 */
public record LandingMetricsResponse(
        long totalRuns,
        Double successRate,
        long reposOrchestrated,
        Long medianRunSeconds,
        Instant generatedAt,
        long cacheTtlSeconds) {}
