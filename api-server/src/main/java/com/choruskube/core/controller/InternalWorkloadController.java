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

/**
 * Internal REST controller for workload execution operations.
 *
 * <p>These endpoints are called by the orchestrator to delegate container
 * creation/management to the API server. Authentication is handled by
 * {@link com.choruskube.core.config.InternalAuthFilter} (Tier 1: orchestrator secret).
 */
@RestController
@RequestMapping("/internal/workloads")
public class InternalWorkloadController {

    private final WorkloadService workloadService;

    public InternalWorkloadController(WorkloadService workloadService) {
        this.workloadService = workloadService;
    }

    /**
     * Creates a workload (launches an agent container) and atomically updates
     * the node execution with pod_name, job_secret_hash, and status=running.
     */
    @PostMapping("/{runId}/{nodeExecId}")
    @ResponseStatus(HttpStatus.CREATED)
    public CreateWorkloadResponse createWorkload(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody CreateWorkloadRequest request) {
        return workloadService.createWorkload(runId, nodeExecId, request);
    }

    /** Cleans up all resources associated with a completed execution. */
    @DeleteMapping("/{executionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cleanupWorkload(@PathVariable UUID executionId) {
        workloadService.cleanupWorkload(executionId);
    }

    /** Returns recent log output from the agent container. */
    @GetMapping("/{executionId}/logs")
    public WorkloadLogsResponse getWorkloadLogs(
            @PathVariable UUID executionId, @RequestParam(defaultValue = "50") int tailLines) {
        return workloadService.getWorkloadLogs(executionId, tailLines);
    }

    /** Stops a running execution. */
    @PostMapping("/{executionId}/terminate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void terminateWorkload(@PathVariable UUID executionId) {
        workloadService.terminateWorkload(executionId);
    }

    /** Returns info about all running/completed executions. */
    @GetMapping
    public List<ExecutionInfo> listWorkloads() {
        return workloadService.listWorkloads();
    }

    /** Checks executor backend connectivity. */
    @GetMapping("/health")
    public void healthCheck() {
        workloadService.healthCheck();
    }
}
