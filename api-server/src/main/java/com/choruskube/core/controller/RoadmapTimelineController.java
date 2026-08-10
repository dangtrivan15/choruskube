package com.choruskube.core.controller;

import com.choruskube.core.dto.RoadmapTimelineResponse;
import com.choruskube.core.service.RoadmapTimelineService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoadmapTimelineController {

    private final RoadmapTimelineService service;

    public RoadmapTimelineController(RoadmapTimelineService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/roadmap/timeline")
    public RoadmapTimelineResponse getTimeline() {
        return service.getTimeline();
    }
}
