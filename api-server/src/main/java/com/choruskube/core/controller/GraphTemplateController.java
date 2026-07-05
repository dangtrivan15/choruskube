package com.choruskube.core.controller;

import com.choruskube.core.dto.*;
import com.choruskube.core.service.GraphTemplateService;
import com.choruskube.core.service.TemplateEdgeService;
import com.choruskube.core.service.TemplateNodeService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/graph-templates")
public class GraphTemplateController {

    private final GraphTemplateService templateService;
    private final TemplateNodeService nodeService;
    private final TemplateEdgeService edgeService;

    public GraphTemplateController(
            GraphTemplateService templateService, TemplateNodeService nodeService, TemplateEdgeService edgeService) {
        this.templateService = templateService;
        this.nodeService = nodeService;
        this.edgeService = edgeService;
    }

    // --- Graph Templates ---

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public Page<GraphTemplateResponse> listTemplates(
            @RequestParam(defaultValue = "false") boolean latestOnly,
            @RequestParam(required = false) String name,
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return templateService.list(latestOnly, name, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}")
    public GraphTemplateResponse getTemplate(@PathVariable UUID id) {
        return templateService.get(id);
    }

    // --- Template Nodes ---

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/nodes")
    public List<TemplateNodeResponse> listNodes(@PathVariable UUID id) {
        return nodeService.list(id);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/nodes/{nodeId}")
    public TemplateNodeResponse getNode(@PathVariable UUID id, @PathVariable UUID nodeId) {
        return nodeService.get(id, nodeId);
    }

    // --- Template Edges ---

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/edges")
    public List<TemplateEdgeResponse> listEdges(@PathVariable UUID id) {
        return edgeService.list(id);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/edges/{edgeId}")
    public TemplateEdgeResponse getEdge(@PathVariable UUID id, @PathVariable UUID edgeId) {
        return edgeService.get(id, edgeId);
    }
}
