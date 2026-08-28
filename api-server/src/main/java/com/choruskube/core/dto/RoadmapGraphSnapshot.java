package com.choruskube.core.dto;

import java.util.List;

public record RoadmapGraphSnapshot(
        EpicResponse epic,
        List<StoryResponse> stories,
        List<TaskResponse> tasks,
        List<DependencyEdgeResponse> dependencies,
        List<ExternalBlockerRef> externalBlockers) {}
