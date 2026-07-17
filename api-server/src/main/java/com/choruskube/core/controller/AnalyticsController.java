package com.choruskube.core.controller;

import com.choruskube.core.dto.*;
import com.choruskube.core.service.AnalyticsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/overview")
    public AnalyticsOverviewResponse getOverview(@RequestParam(required = false, defaultValue = "30d") String period) {
        return analyticsService.getOverview(period);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/runs")
    public RunTrendResponse getRunTrend(@RequestParam(required = false, defaultValue = "30d") String period) {
        return analyticsService.getRunTrend(period);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/templates")
    public TemplateAnalyticsResponse getTemplateAnalytics(
            @RequestParam(required = false, defaultValue = "30d") String period) {
        return analyticsService.getTemplateAnalytics(period);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/nodes")
    public NodeAnalyticsResponse getNodeAnalytics(@RequestParam(required = false, defaultValue = "30d") String period) {
        return analyticsService.getNodeAnalytics(period);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/bottlenecks")
    public BottleneckResponse getBottlenecks(@RequestParam(required = false, defaultValue = "30d") String period) {
        return analyticsService.getBottlenecks(period);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/roadmap/status-counts")
    public RoadmapStatusCountsResponse getRoadmapStatusCounts() {
        return analyticsService.getRoadmapStatusCounts();
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/roadmap/throughput")
    public RoadmapThroughputResponse getRoadmapThroughput(
            @RequestParam(required = false, defaultValue = "30d") String period) {
        return analyticsService.getRoadmapThroughput(period);
    }
}
