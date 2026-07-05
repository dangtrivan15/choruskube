package com.choruskube.core.dto;

public record BottleneckNode(
        String label,
        double avgDurationSeconds,
        double p50DurationSeconds,
        double p95DurationSeconds,
        long sampleSize) {}
