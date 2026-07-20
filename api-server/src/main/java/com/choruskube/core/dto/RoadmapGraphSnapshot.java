package com.choruskube.core.dto;

import java.util.List;

/** Full graph view of an Epic's Story/Task tree plus its "blocking" dependency edges. */
public record RoadmapGraphSnapshot(
        EpicResponse epic,
        List<StoryResponse> stories,
        List<TaskResponse> tasks,
        List<DependencyEdgeResponse> dependencies,
        List<ExternalBlockerRef> externalBlockers) {}
