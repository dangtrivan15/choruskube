package com.choruskube.core.dto;

public record TemplateAnalytics(
        String templateName, long runCount, long completedCount, long failedCount, double successRate) {}
