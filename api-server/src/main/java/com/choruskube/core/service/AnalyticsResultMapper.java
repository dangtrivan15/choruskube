package com.choruskube.core.service;

import com.choruskube.core.dto.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class AnalyticsResultMapper {

    private AnalyticsResultMapper() {}

    /**
     * Parses a period string into an Instant cutoff. Supported formats: "7d", "30d", "90d", "24h".
     * Defaults to 30d for null or unrecognized input.
     */
    public static Instant parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            return Instant.now().minus(30, ChronoUnit.DAYS);
        }
        String trimmed = period.trim().toLowerCase();
        try {
            if (trimmed.endsWith("d")) {
                int days = Integer.parseInt(trimmed.substring(0, trimmed.length() - 1));
                if (days <= 0 || days > 365) {
                    return Instant.now().minus(30, ChronoUnit.DAYS);
                }
                return Instant.now().minus(days, ChronoUnit.DAYS);
            } else if (trimmed.endsWith("h")) {
                int hours = Integer.parseInt(trimmed.substring(0, trimmed.length() - 1));
                if (hours <= 0 || hours > 8760) {
                    return Instant.now().minus(30, ChronoUnit.DAYS);
                }
                return Instant.now().minus(hours, ChronoUnit.HOURS);
            }
        } catch (NumberFormatException e) {
            // fall through to default
        }
        return Instant.now().minus(30, ChronoUnit.DAYS);
    }

    public static AnalyticsOverviewResponse toOverview(Object[] row) {
        if (row == null || row.length == 0) {
            return new AnalyticsOverviewResponse(0, 0, 0, 0.0, null, null, null);
        }
        Object[] data = unwrapRow(row);
        long totalRuns = toLong(data[0]);
        long completedRuns = toLong(data[1]);
        long failedRuns = toLong(data[2]);
        double avgDuration = toDouble(data[3]);
        double p50Duration = toDouble(data[4]);
        double p95Duration = toDouble(data[5]);

        double successRate = totalRuns > 0 ? round((double) completedRuns / totalRuns * 100.0) : 0.0;

        return new AnalyticsOverviewResponse(
                totalRuns,
                completedRuns,
                failedRuns,
                successRate,
                avgDuration > 0 ? round(avgDuration) : null,
                p50Duration > 0 ? round(p50Duration) : null,
                p95Duration > 0 ? round(p95Duration) : null);
    }

    public static RunTrendResponse toRunTrend(List<Object[]> rows) {
        List<RunTrendPoint> points = rows.stream()
                .map(r -> new RunTrendPoint(
                        (String) r[0], // day string
                        toLong(r[1]), // total
                        toLong(r[2]), // completed
                        toLong(r[3]) // failed
                        ))
                .toList();
        return new RunTrendResponse(points);
    }

    public static TemplateAnalyticsResponse toTemplateAnalytics(List<Object[]> rows) {
        List<TemplateAnalytics> templates = rows.stream()
                .map(r -> {
                    String templateName = (String) r[0];
                    long runCount = toLong(r[1]);
                    long completedCount = toLong(r[2]);
                    long failedCount = toLong(r[3]);
                    double successRate = runCount > 0 ? round((double) completedCount / runCount * 100.0) : 0.0;
                    return new TemplateAnalytics(templateName, runCount, completedCount, failedCount, successRate);
                })
                .toList();
        return new TemplateAnalyticsResponse(templates);
    }

    public static NodeAnalyticsResponse toNodeAnalytics(List<Object[]> rows) {
        List<NodeAnalytics> nodes = rows.stream()
                .map(r -> {
                    String label = (String) r[0];
                    long executionCount = toLong(r[1]);
                    long completedCount = toLong(r[2]);
                    long failedCount = toLong(r[3]);
                    double successRate =
                            executionCount > 0 ? round((double) completedCount / executionCount * 100.0) : 0.0;
                    return new NodeAnalytics(label, executionCount, completedCount, failedCount, successRate);
                })
                .toList();
        return new NodeAnalyticsResponse(nodes);
    }

    public static BottleneckResponse toBottlenecks(List<Object[]> rows) {
        List<BottleneckNode> bottlenecks = rows.stream()
                .map(r -> new BottleneckNode(
                        (String) r[0], // label
                        round(toDouble(r[1])), // avg duration
                        round(toDouble(r[2])), // p50 duration
                        round(toDouble(r[3])), // p95 duration
                        toLong(r[4]) // sample size
                        ))
                .toList();
        return new BottleneckResponse(bottlenecks);
    }

    public static RoadmapStatusCountsResponse toRoadmapStatusCounts(List<Object[]> rows) {
        long total = 0;
        List<RoadmapStatusCount> statuses = new ArrayList<>();
        for (Object[] r : rows) {
            String status = (String) r[0];
            long count = toLong(r[1]);
            total += count;
            statuses.add(new RoadmapStatusCount(status, count));
        }
        return new RoadmapStatusCountsResponse(total, statuses);
    }

    public static RoadmapThroughputResponse toRoadmapThroughput(List<Object[]> rows) {
        List<RoadmapThroughputPoint> points = rows.stream()
                .map(r -> new RoadmapThroughputPoint(
                        (String) r[0], // day string
                        toLong(r[1]) // count
                        ))
                .toList();
        return new RoadmapThroughputResponse(points);
    }

    /**
     * Unwraps the result row. For single-row aggregate queries, some JPA implementations return
     * Object[] directly while others wrap it.
     */
    private static Object[] unwrapRow(Object[] row) {
        if (row.length == 1 && row[0] instanceof Object[]) {
            return (Object[]) row[0];
        }
        return row;
    }

    static long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }

    static double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(value.toString());
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
