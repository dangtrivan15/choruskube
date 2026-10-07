package com.choruskube.core.controller;

import com.choruskube.core.dto.RoadmapImportResponse;
import com.choruskube.core.service.RoadmapImportService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RoadmapImportController {

    private final RoadmapImportService service;

    public RoadmapImportController(RoadmapImportService service) {
        this.service = service;
    }

    /**
     * Imports a roadmap document (the {@code propose-roadmap} schema) into a software project. The
     * body is bound as raw JSON so type errors come back as path-prefixed entries in the 400's
     * {@code errors[]} alongside the validator's own.
     */
    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/api/v1/roadmap/import")
    public RoadmapImportResponse importRoadmap(
            @RequestParam UUID softwareProjectId,
            @RequestParam(defaultValue = "false") boolean dryRun,
            @RequestBody JsonNode body) {
        return service.importDocument(softwareProjectId, body, dryRun);
    }
}
