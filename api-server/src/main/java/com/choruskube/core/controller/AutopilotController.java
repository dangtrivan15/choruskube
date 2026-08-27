package com.choruskube.core.controller;

import com.choruskube.core.dto.AutopilotStatusResponse;
import com.choruskube.core.dto.AutopilotUpdateRequest;
import com.choruskube.core.service.AutopilotService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configure-and-observe surface for the Autopilot singleton (spec). Reads are org-read;
 * every mutation — including the manual tick, which is e2e-facing but still changes state — is
 * org-operate.
 */
@RestController
@RequestMapping("/api/v1/autopilot")
public class AutopilotController {

    private final AutopilotService service;

    public AutopilotController(AutopilotService service) {
        this.service = service;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public AutopilotStatusResponse get() {
        return service.getStatus();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping
    public AutopilotStatusResponse update(@Valid @RequestBody AutopilotUpdateRequest request) {
        return service.update(request.maxParallel());
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/engage")
    public AutopilotStatusResponse engage() {
        return service.engage();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/disengage")
    public AutopilotStatusResponse disengage() {
        return service.disengage();
    }

    /**
     * Runs one tick synchronously, then re-reads status. That is a second readiness sweep on top
     * of the one the tick itself just did — accepted, because this endpoint is manual and
     * e2e-facing, not part of the scheduled path {@link AutopilotService#tick()} already covers.
     */
    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/tick")
    public AutopilotStatusResponse tickNow() {
        service.tick();
        return service.getStatus();
    }
}
