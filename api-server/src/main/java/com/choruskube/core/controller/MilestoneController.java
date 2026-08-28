package com.choruskube.core.controller;

import com.choruskube.core.dto.MilestoneAtRiskItemsResponse;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.MilestoneUpdateRequest;
import com.choruskube.core.service.MilestoneService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorization ladder mirrors {@code EpicController}: reads are org-read, create is
 * org-operate, rename/delete are org-admin.
 */
@RestController
@RequestMapping("/api/v1/milestones")
public class MilestoneController {

    private final MilestoneService service;

    public MilestoneController(MilestoneService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping
    public ResponseEntity<MilestoneResponse> create(@Valid @RequestBody MilestoneRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public Page<MilestoneResponse> list(
            @RequestParam(required = false) UUID softwareProjectId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(softwareProjectId, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}")
    public MilestoneResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/at-risk-items")
    public MilestoneAtRiskItemsResponse getAtRiskItems(@PathVariable UUID id) {
        return service.getAtRiskItems(id);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @PutMapping("/{id}")
    public MilestoneResponse update(@PathVariable UUID id, @Valid @RequestBody MilestoneUpdateRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
