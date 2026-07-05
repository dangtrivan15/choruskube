package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.exception.GlobalExceptionHandler;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.StoragePrefixResolver;
import com.choruskube.core.service.UploadService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone (no Spring context) unit tests for GateAttachmentController. Covers happy path, 404
 * on unknown run, 403 on wrong org access, and 404 when the nodeExecId does not belong to the
 * specified run.
 */
class GateAttachmentControllerTest {

    private UploadService uploadService;
    private WorkflowRunRepository runRepo;
    private NodeExecutionRepository nodeExecRepo;
    private AuthorizationService authService;
    private StoragePrefixResolver storagePrefixResolver;
    private MockMvc mockMvc;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID NODE_EXEC_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        uploadService = mock(UploadService.class);
        runRepo = mock(WorkflowRunRepository.class);
        nodeExecRepo = mock(NodeExecutionRepository.class);
        authService = mock(AuthorizationService.class);
        storagePrefixResolver = mock(StoragePrefixResolver.class);
        when(storagePrefixResolver.storagePrefixForRun(any())).thenReturn("acme");

        GateAttachmentController controller =
                new GateAttachmentController(uploadService, runRepo, nodeExecRepo, authService, storagePrefixResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private WorkflowRun buildRun() {
        WorkflowRun run = new WorkflowRun();
        return run;
    }

    private NodeExecution buildNodeExec(UUID workflowRunId) {
        NodeExecution ne = new NodeExecution();
        ne.setWorkflowRunId(workflowRunId);
        return ne;
    }

    @Test
    void uploadGateAttachments_happyPath_returnsAttachmentRefs() throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun()));
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(buildNodeExec(RUN_ID)));
        when(uploadService.uploadGateFiles(eq("acme"), eq(RUN_ID), eq(NODE_EXEC_ID), anyList()))
                .thenReturn("{\"doc.pdf\":\"acme/runs/" + RUN_ID + "/gate-attachments/" + NODE_EXEC_ID + "/doc.pdf\"}");

        MockMultipartFile file = new MockMultipartFile("files", "doc.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentRefs").exists());
    }

    @Test
    void uploadGateAttachments_runNotFound_returns404() throws Exception {
        when(runRepo.findById(any())).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile("files", "doc.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/runs/" + UUID.randomUUID() + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(file))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadGateAttachments_wrongOrg_returns403() throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun()));
        doThrow(new com.choruskube.core.exception.ForbiddenException("Access denied"))
                .when(authService)
                .checkOrgAccess(anyString(), eq(RUN_ID));

        MockMultipartFile file = new MockMultipartFile("files", "doc.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(file))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadGateAttachments_nodeExecNotFound_returns404() throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun()));
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile("files", "doc.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(file))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadGateAttachments_nodeExecBelongsToDifferentRun_returns404() throws Exception {
        UUID otherRunId = UUID.randomUUID();
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun()));
        // nodeExec belongs to a different run
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(buildNodeExec(otherRunId)));

        MockMultipartFile file = new MockMultipartFile("files", "doc.pdf", "application/pdf", "content".getBytes());

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(file))
                .andExpect(status().isNotFound());
    }
}
