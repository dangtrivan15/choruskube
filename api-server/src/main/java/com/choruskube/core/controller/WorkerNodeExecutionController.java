package com.choruskube.core.controller;

import com.choruskube.core.config.WorkerAuthFilter;
import com.choruskube.core.dto.InternalUpdateNodeExecutionRequest;
import com.choruskube.core.dto.InternalWriteLogRequest;
import com.choruskube.core.dto.NodeExecutionResponse;
import com.choruskube.core.service.InternalRunService;
import com.choruskube.core.service.SingleFleetWorkerAuthorizer;
import com.choruskube.core.service.WorkerAuthorizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The node-execution operations a Worker's callback handler needs: whether an execution is already
 * finalized, recording its outcome, and appending to its execution log. These mirror the equivalent
 * {@code /internal/runs/**} routes on {@link InternalRunService}, but a Worker authenticates with a
 * Fleet credential rather than {@code ORCHESTRATOR_SECRET}, so it needs its own {@code /worker/**}
 * surface with the same auth pattern as {@link WorkerWorkloadController}.
 */
@RestController
@RequestMapping("/worker/runs/{runId}/node-executions/{nodeExecId}")
public class WorkerNodeExecutionController {

    private final WorkerAuthorizer authorizer;
    private final InternalRunService runService;

    public WorkerNodeExecutionController(
            ObjectProvider<WorkerAuthorizer> authorizerProvider,
            @Value("${worker.registration.token:}") String registrationToken,
            InternalRunService runService) {
        this.authorizer = authorizerProvider.getIfAvailable(() -> new SingleFleetWorkerAuthorizer(registrationToken));
        this.runService = runService;
    }

    @GetMapping
    public ResponseEntity<NodeExecutionResponse> getNodeExecution(
            HttpServletRequest httpRequest, @PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        String credential = credentialOf(httpRequest);
        if (credential == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authorizer.requireMayActOn(credential, runId);
        return ResponseEntity.ok(runService.getNodeExecution(runId, nodeExecId));
    }

    @PutMapping("/status")
    public ResponseEntity<NodeExecutionResponse> updateNodeExecutionStatus(
            HttpServletRequest httpRequest,
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody InternalUpdateNodeExecutionRequest request) {
        String credential = credentialOf(httpRequest);
        if (credential == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authorizer.requireMayActOn(credential, runId);
        return ResponseEntity.ok(runService.updateNodeExecutionStatus(runId, nodeExecId, request));
    }

    @PostMapping("/logs")
    public ResponseEntity<Void> writeExecutionLog(
            HttpServletRequest httpRequest,
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody InternalWriteLogRequest request) {
        String credential = credentialOf(httpRequest);
        if (credential == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        authorizer.requireMayActOn(credential, runId);
        runService.writeExecutionLog(runId, nodeExecId, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
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
