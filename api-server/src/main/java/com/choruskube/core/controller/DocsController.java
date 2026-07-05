package com.choruskube.core.controller;

import com.choruskube.core.dto.DocsIndexEntry;
import com.choruskube.core.dto.DocsPageResponse;
import com.choruskube.core.service.DocsService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/docs")
public class DocsController {

    private final DocsService docsService;

    public DocsController(DocsService docsService) {
        this.docsService = docsService;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canRead()")
    public ResponseEntity<List<DocsIndexEntry>> listDocs() {
        return ResponseEntity.ok(docsService.getIndex());
    }

    @GetMapping("/{slug}")
    @PreAuthorize("@orgSecurity.canRead()")
    public ResponseEntity<DocsPageResponse> getDocPage(@PathVariable String slug) {
        return docsService
                .getPage(slug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
