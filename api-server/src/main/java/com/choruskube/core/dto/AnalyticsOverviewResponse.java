package com.choruskube.core.dto;

public record AnalyticsOverviewResponse(
        long totalRuns,
        long completedRuns,
        long failedRuns,
        double successRate,
        Double avgDurationSeconds,
        Double p50DurationSeconds,
        Double p95DurationSeconds) {}
