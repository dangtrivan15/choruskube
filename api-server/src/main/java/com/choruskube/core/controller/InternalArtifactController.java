package com.choruskube.core.controller;

import com.choruskube.core.dto.PresignResponse;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.ArtifactUploadBudget;
import com.choruskube.core.service.ArtifactUploadBudget.Admission;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.PresignService;
import com.choruskube.core.service.RunStorageScope;
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
    private final RunStorageScope runStorageScope;
    private final ArtifactUploadBudget uploadBudget;

    public InternalArtifactController(
            PresignService presignService,
            NodeExecutionRepository execRepo,
            WorkflowRunRepository runRepo,
            AuthorizationService authService,
            RunStorageScope runStorageScope,
            ArtifactUploadBudget uploadBudget) {
        this.presignService = presignService;
        this.execRepo = execRepo;
        this.runRepo = runRepo;
        this.authService = authService;
        this.runStorageScope = runStorageScope;
        this.uploadBudget = uploadBudget;
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/presign")
    public ResponseEntity<?> presign(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestParam(required = false) String path,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String size) {

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
        boolean isPut = method.equalsIgnoreCase("PUT");

        // A PUT's declared size is what the budget books and the store enforces; a GET carries no
        // body, so this is checked only for PUT, before anything else, alongside path/method.
        long declaredBytes = 0;
        if (isPut) {
            Long parsed = parseDeclaredSize(size);
            if (parsed == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "error",
                                "Missing or invalid required parameter for PUT: size (the upload's exact byte count)"));
            }
            declaredBytes = parsed;
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

        // RunScopeInterceptor already pins the path's runId to this execution's run, so agentRun
        // and targetRun are the same row; this org check is the backstop if that guard ever stops
        // covering this route.
        authService.assertSameOrg("workflow_run", agentRun.getId(), "workflow_run", targetRun.getId());

        if (!runStorageScope.contains(agentExec.getWorkflowRunId(), path)) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied: path outside allowed scope"));
        }
        if (hasEmptyOrDotSegment(path)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid path: must not contain an empty or '.'/'..' segment"));
        }

        if (isPut) {
            Admission admission = uploadBudget.admit(agentExec.getWorkflowRunId(), path, declaredBytes);
            if (admission instanceof Admission.RunClosed closed) {
                return ResponseEntity.status(409)
                        .body(Map.of("error", "Run is closed (" + closed.status() + "); uploads are refused"));
            }
            if (admission instanceof Admission.OverBudget overBudget) {
                return ResponseEntity.status(413)
                        .body(Map.of(
                                "error",
                                "Upload would exceed the run's upload budget (budgetBytes="
                                        + overBudget.budgetBytes()
                                        + ", bookedBytes="
                                        + overBudget.bookedBytes()
                                        + ", declaredBytes="
                                        + overBudget.declaredBytes()
                                        + ")",
                                "budgetBytes",
                                overBudget.budgetBytes(),
                                "bookedBytes",
                                overBudget.bookedBytes(),
                                "declaredBytes",
                                overBudget.declaredBytes()));
            }
        }

        Long contentLength = isPut ? declaredBytes : null;
        String url = presignService.generatePresignedUrl(
                agentExec.getWorkflowRunId(), path, method.toUpperCase(), contentLength);
        return ResponseEntity.ok(new PresignResponse(url));
    }

    // Plain ASCII digits only: no sign, no decimal point, no leading/trailing whitespace. Rejects
    // a value too large for `long` instead of silently wrapping it, so an oversized declaration
    // fails closed rather than booking a wrapped-negative or truncated size.
    private static Long parseDeclaredSize(String size) {
        if (size == null || size.isBlank() || !size.matches("[0-9]+")) {
            return null;
        }
        try {
            return Long.parseLong(size);
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    // A proxy in front of the store that normalises paths rewrites a key with an empty or dot
    // segment, and the rewritten request no longer matches its signature; refusing the key here
    // keeps every key working whichever endpoint the run's URLs name.
    private static boolean hasEmptyOrDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
