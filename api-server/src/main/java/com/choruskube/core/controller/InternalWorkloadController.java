package com.choruskube.core.controller;

import com.choruskube.core.dto.CreateWorkloadRequest;
import com.choruskube.core.dto.CreateWorkloadResponse;
import com.choruskube.core.dto.WorkloadLogsResponse;
import com.choruskube.core.executor.ExecutionInfo;
import com.choruskube.core.service.WorkloadService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/workloads")
public class InternalWorkloadController {

    private final WorkloadService workloadService;

    public InternalWorkloadController(WorkloadService workloadService) {
        this.workloadService = workloadService;
    }

    @PostMapping("/{runId}/{nodeExecId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateWorkloadResponse createWorkload(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody CreateWorkloadRequest request) {
        return workloadService.createWorkload(runId, nodeExecId, request);
    }

    @DeleteMapping("/{executionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cleanupWorkload(@PathVariable UUID executionId) {
        workloadService.cleanupWorkload(executionId);
    }

    @GetMapping("/{executionId}/logs")
    public WorkloadLogsResponse getWorkloadLogs(
            @PathVariable UUID executionId, @RequestParam(defaultValue = "50") int tailLines) {
        return workloadService.getWorkloadLogs(executionId, tailLines);
    }

    @PostMapping("/{executionId}/terminate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void terminateWorkload(@PathVariable UUID executionId) {
        workloadService.terminateWorkload(executionId);
    }

    @GetMapping
    public List<ExecutionInfo> listWorkloads() {
        return workloadService.listWorkloads();
    }

    @GetMapping("/health")
    public void healthCheck() {
        workloadService.healthCheck();
    }
}
