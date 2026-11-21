package com.choruskube.core.controller;

import com.choruskube.core.dto.AttachmentRefsResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.StoragePrefixResolver;
import com.choruskube.core.service.UploadService;
import com.choruskube.core.util.NodeExecutionUtil;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/runs/{runId}/nodes/{nodeExecId}")
public class GateAttachmentController {

    private final UploadService uploadService;
    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository nodeExecRepo;
    private final AuthorizationService authService;
    private final StoragePrefixResolver storagePrefixResolver;

    public GateAttachmentController(
            UploadService uploadService,
            WorkflowRunRepository runRepo,
            NodeExecutionRepository nodeExecRepo,
            AuthorizationService authService,
            StoragePrefixResolver storagePrefixResolver) {
        this.uploadService = uploadService;
        this.runRepo = runRepo;
        this.nodeExecRepo = nodeExecRepo;
        this.authService = authService;
        this.storagePrefixResolver = storagePrefixResolver;
    }

    @PostMapping("/attachments")
    @PreAuthorize("@orgSecurity.canOperate()")
    public ResponseEntity<AttachmentRefsResponse> uploadGateAttachments(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestParam("files") List<MultipartFile> files)
            throws Exception {
        WorkflowRun run = runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);
        // Validate that the node execution belongs to this run (prevents cross-run upload path injection)
        NodeExecution nodeExec = nodeExecRepo
                .findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        NodeExecutionUtil.requireInRun(nodeExec, runId);

        // Below the org check, so a foreign caller never learns the run's status. A closed run's tree
        // may already be expired and is never revisited, so a write there is never deleted; see
        // docs/decisions/2026-11-21---05-gate-attachments-are-admitted-only-at-an-open-gate.md.
        if (WorkflowRunStatusGroups.TERMINAL.contains(run.getStatus())) {
            throw new ConflictException("Cannot upload gate attachments: run has closed (" + run.getStatus() + ")");
        }
        if (nodeExec.getStatus() != NodeExecutionStatus.awaiting_human) {
            throw new ConflictException(
                    "Cannot upload gate attachments: node execution is not awaiting a human decision");
        }

        String orgSlug = storagePrefixResolver.storagePrefixForRun(runId);
        String attachmentRefs = uploadService.uploadGateFiles(orgSlug, runId, nodeExecId, files);
        return ResponseEntity.ok(new AttachmentRefsResponse(attachmentRefs));
    }
}
