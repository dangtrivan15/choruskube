package com.choruskube.core.dto;

import java.util.List;

public record RoadmapStatusCountsResponse(long total, List<RoadmapStatusCount> statuses) {}
