package com.choruskube.core.controller;

import com.choruskube.core.dto.GateStampResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.GateRecheckService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs/{runId}/gate-stamps")
public class GateStampController {

    private final WorkflowRunRepository runRepo;
    private final AuthorizationService authService;
    private final GateRecheckService gateRecheck;

    public GateStampController(
            WorkflowRunRepository runRepo, AuthorizationService authService, GateRecheckService gateRecheck) {
        this.runRepo = runRepo;
        this.authService = authService;
        this.gateRecheck = gateRecheck;
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public List<GateStampResponse> list(@PathVariable UUID runId) {
        runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);
        return gateRecheck.listForRun(runId).stream()
                .map(GateStampResponse::from)
                .toList();
    }
}
