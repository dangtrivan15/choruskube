package com.choruskube.core.dto;

public record NodeAnalytics(
        String label, long executionCount, long completedCount, long failedCount, double successRate) {}
