package com.choruskube.core.controller;

import com.choruskube.core.dto.*;
import com.choruskube.core.service.RunService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping
    public ResponseEntity<RunResponse> startRun(@Valid @RequestBody CreateRunRequest request) {
        RunResponse response = runService.startRun(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping
    public Page<RunSummary> listRuns(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String name,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return runService.listRuns(status, name, pageable);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> getRun(@PathVariable UUID id) {
        RunResponse response = runService.getRun(id);
        return ResponseEntity.ok(response);
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PatchMapping("/{id}/name")
    public ResponseEntity<Void> rename(@PathVariable UUID id, @Valid @RequestBody UpdateRunNameRequest request) {
        runService.renameRun(id, request.name());
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable UUID id) {
        runService.pauseRun(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable UUID id) {
        runService.resumeRun(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        runService.cancelRun(id);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{id}/nodes/{nodeExecId}/retry")
    public ResponseEntity<Void> retryNode(@PathVariable UUID id, @PathVariable UUID nodeExecId) {
        runService.retryNode(id, nodeExecId);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("@orgSecurity.canOperate()")
    @PostMapping("/{id}/nodes/{nodeExecId}/signal")
    public ResponseEntity<Void> signal(
            @PathVariable UUID id, @PathVariable UUID nodeExecId, @Valid @RequestBody SignalRequest request) {
        runService.signalHumanDecision(id, nodeExecId, request);
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/nodes/{nodeExecId}/logs")
    public List<ExecutionLogResponse> getExecutionLogs(@PathVariable UUID id, @PathVariable UUID nodeExecId) {
        return runService.getExecutionLogs(nodeExecId);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/review-history")
    public List<ReviewHistoryResponse> getReviewHistory(
            @PathVariable UUID id, @RequestParam(required = false) String loopGroup) {
        return runService.getReviewHistory(id, loopGroup);
    }

    @PreAuthorize("@orgSecurity.canRead()")
    @GetMapping("/{id}/pull-requests")
    public List<RunPullRequestResponse> getPullRequests(@PathVariable UUID id) {
        return runService.getPullRequests(id);
    }
}
