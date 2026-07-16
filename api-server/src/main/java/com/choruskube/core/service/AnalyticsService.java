package com.choruskube.core.service;

import com.choruskube.core.dto.*;

public interface AnalyticsService {

    AnalyticsOverviewResponse getOverview(String period);

    RunTrendResponse getRunTrend(String period);

    TemplateAnalyticsResponse getTemplateAnalytics(String period);

    NodeAnalyticsResponse getNodeAnalytics(String period);

    BottleneckResponse getBottlenecks(String period);

    RoadmapStatusCountsResponse getRoadmapStatusCounts();

    RoadmapThroughputResponse getRoadmapThroughput(String period);
}
