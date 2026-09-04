package com.choruskube.core.controller;

import com.choruskube.core.config.WorkerAuthFilter;
import com.choruskube.core.dto.CompleteWorkloadRequest;
import com.choruskube.core.dto.CreateWorkloadRequest;
import com.choruskube.core.dto.PrepareWorkloadResponse;
import com.choruskube.core.service.SingleFleetWorkerAuthorizer;
import com.choruskube.core.service.WorkerAuthorizer;
import com.choruskube.core.service.WorkloadService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The workload operations a Worker may perform, and the whole of them.
 *
 * <p>They live under {@code /worker/**} rather than beside the orchestrator's routes so that what a
 * Worker can reach is a route list rather than a pattern to reason about. Every route names its run,
 * which is what lets one authorization call cover the surface.
 */
@RestController
@RequestMapping("/worker/runs/{runId}/node-executions/{nodeExecId}/workload")
public class WorkerWorkloadController {

    private final WorkerAuthorizer authorizer;
    private final WorkloadService workloadService;

    public WorkerWorkloadController(
            ObjectProvider<WorkerAuthorizer> authorizerProvider,
            @Value("${worker.registration.token:}") String registrationToken,
            WorkloadService workloadService) {
        this.authorizer = authorizerProvider.getIfAvailable(() -> new SingleFleetWorkerAuthorizer(registrationToken));
        this.workloadService = workloadService;
    }

    @PostMapping("/prepare")
    public ResponseEntity<PrepareWorkloadResponse> prepareWorkload(
            HttpServletRequest httpRequest,
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody CreateWorkloadRequest request) {
        String credential = credentialOf(httpRequest);
        if (credential == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authorizer.requireMayActOn(credential, runId);
        return ResponseEntity.ok(workloadService.prepareWorkload(runId, nodeExecId, request));
    }

    @PostMapping("/complete")
    public ResponseEntity<Void> completeWorkload(
            HttpServletRequest httpRequest,
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody CompleteWorkloadRequest request) {
        String credential = credentialOf(httpRequest);
        if (credential == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authorizer.requireMayActOn(credential, runId);
        workloadService.completeWorkload(runId, nodeExecId, request);
        return ResponseEntity.ok().build();
    }

    /**
     * Null means no credential was presented. {@code WorkerAuthFilter} guards {@code /worker/**} by
     * raw URI prefix while Spring MVC routes on the decoded path, so an encoded path reaches here
     * unfiltered — never assume a filter we cannot see from here actually ran.
     */
    private static String credentialOf(HttpServletRequest httpRequest) {
        String credential = (String) httpRequest.getAttribute(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE);
        return (credential == null || credential.isBlank()) ? null : credential;
    }
}
