package com.choruskube.core.controller;

import com.choruskube.core.dto.RoadmapGraphSnapshot;
import com.choruskube.core.service.RoadmapGraphService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoadmapGraphController {

    private final RoadmapGraphService service;

    public RoadmapGraphController(RoadmapGraphService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/api/v1/epics/{epicId}/graph")
    public RoadmapGraphSnapshot getGraph(@PathVariable UUID epicId) {
        return service.getGraph(epicId);
    }
}
