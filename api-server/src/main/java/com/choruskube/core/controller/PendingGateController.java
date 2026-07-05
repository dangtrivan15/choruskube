package com.choruskube.core.controller;

import com.choruskube.core.dto.PendingGateCountResponse;
import com.choruskube.core.dto.PendingGateResponse;
import com.choruskube.core.service.PendingGateService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pending-gates")
public class PendingGateController {

    private final PendingGateService pendingGateService;

    public PendingGateController(PendingGateService pendingGateService) {
        this.pendingGateService = pendingGateService;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public Page<PendingGateResponse> getPendingGates(
            @PageableDefault(size = 20, sort = "startedAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return pendingGateService.getPendingGates(pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/count")
    public PendingGateCountResponse getPendingGateCount() {
        return pendingGateService.getPendingGateCount();
    }
}
