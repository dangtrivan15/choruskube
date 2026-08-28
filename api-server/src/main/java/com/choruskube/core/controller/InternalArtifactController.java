package com.choruskube.core.controller;

import com.choruskube.core.dto.PresignResponse;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.PresignService;
import com.choruskube.core.service.StoragePrefixResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/runs")
public class InternalArtifactController {

    private final PresignService presignService;
    private final NodeExecutionRepository execRepo;
    private final WorkflowRunRepository runRepo;
    private final AuthorizationService authService;
    private final StoragePrefixResolver storagePrefixResolver;

    public InternalArtifactController(
            PresignService presignService,
            NodeExecutionRepository execRepo,
            WorkflowRunRepository runRepo,
            AuthorizationService authService,
            StoragePrefixResolver storagePrefixResolver) {
        this.presignService = presignService;
        this.execRepo = execRepo;
        this.runRepo = runRepo;
        this.authService = authService;
        this.storagePrefixResolver = storagePrefixResolver;
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/presign")
    public ResponseEntity<?> presign(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String method) {

        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required parameter: path"));
        }
        if (method == null || method.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing required parameter: method"));
        }
        if (!method.equalsIgnoreCase("GET") && !method.equalsIgnoreCase("PUT")) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid method: " + method + ". Only GET and PUT are allowed."));
        }

        // InternalAuthFilter has already loaded this NodeExecution to validate JOB_SECRET.
        // Reusing it would mean passing it through a request attribute, i.e. editing that
        // filter — which CLAUDE.md gates behind explicit approval. Presign calls are
        // infrequent, so the second lookup stays.
        NodeExecution agentExec = execRepo.findById(nodeExecId).orElse(null);
        if (agentExec == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Node execution not found"));
        }
        WorkflowRun agentRun = runRepo.findById(agentExec.getWorkflowRunId()).orElse(null);
        if (agentRun == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Run not found"));
        }

        WorkflowRun targetRun = runRepo.findById(runId).orElse(null);
        if (targetRun == null) {
            return ResponseEntity.status(403).body(Map.of("error", "Target run not found"));
        }

        // Cross-org check: agent's run and the target run must belong to the same org. Resolved from
        // ownership data by the active strategy (no TenantContext on this JOB_SECRET path); throws
        // ForbiddenException (403) on mismatch under auth, no-op under always-allow.
        authService.assertSameOrg("workflow_run", agentRun.getId(), "workflow_run", targetRun.getId());

        String orgSlug = storagePrefixResolver.storagePrefixForRun(agentRun.getId());

        // The `..` test is not redundant with the prefix test: `<org>/runs/<id>/../../other/x`
        // satisfies startsWith and still escapes the scope.
        String allowedPrefix = orgSlug + "/runs/" + runId + "/";
        if (!path.startsWith(allowedPrefix) || path.contains("..")) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied: path outside allowed scope"));
        }

        String url = presignService.generatePresignedUrl(path, method.toUpperCase());
        return ResponseEntity.ok(new PresignResponse(url));
    }
}
