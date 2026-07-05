package com.choruskube.core.controller;

import com.choruskube.core.dto.FeatureProposalRequest;
import com.choruskube.core.dto.FeatureProposalResponse;
import com.choruskube.core.model.enums.FeatureProposalStatus;
import com.choruskube.core.service.FeatureProposalService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feature-proposals")
public class FeatureProposalController {

    private final FeatureProposalService service;

    public FeatureProposalController(FeatureProposalService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping
    public ResponseEntity<FeatureProposalResponse> create(@Valid @RequestBody FeatureProposalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public Page<FeatureProposalResponse> list(
            @RequestParam(required = false) FeatureProposalStatus status,
            @RequestParam(required = false) String title,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.list(status, title, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}")
    public FeatureProposalResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @PutMapping("/{id}")
    public FeatureProposalResponse update(@PathVariable UUID id, @Valid @RequestBody FeatureProposalRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("@orgSecurity.canAdmin()")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{id}/start")
    public FeatureProposalResponse start(@PathVariable UUID id) {
        return service.start(id);
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/{id}/roll-out")
    public FeatureProposalResponse rollOut(@PathVariable UUID id) {
        return service.rollOut(id);
    }
}
