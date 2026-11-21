package com.choruskube.core.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.exception.GlobalExceptionHandler;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.StoragePrefixResolver;
import com.choruskube.core.service.UploadService;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone (no Spring context) unit tests for GateAttachmentController. Covers happy path, the
 * admission check (an awaiting gate on a run that has not closed), 404 on unknown run, 403 on
 * wrong org access, and 404 when the nodeExecId does not belong to the specified run.
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

    private static Stream<WorkflowRunStatus> activeRunStatuses() {
        return WorkflowRunStatusGroups.ACTIVE.stream();
    }

    private static Stream<WorkflowRunStatus> terminalRunStatuses() {
        return WorkflowRunStatusGroups.TERMINAL.stream();
    }

    private WorkflowRun buildRun() {
        return buildRun(WorkflowRunStatus.running);
    }

    private WorkflowRun buildRun(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setStatus(status);
        return run;
    }

    private NodeExecution buildNodeExec(UUID workflowRunId) {
        return buildNodeExec(workflowRunId, NodeExecutionStatus.awaiting_human);
    }

    private NodeExecution buildNodeExec(UUID workflowRunId, NodeExecutionStatus status) {
        NodeExecution ne = new NodeExecution();
        ne.setWorkflowRunId(workflowRunId);
        ne.setStatus(status);
        return ne;
    }

    private MockMultipartFile sampleFile() {
        return new MockMultipartFile("files", "doc.pdf", "application/pdf", "content".getBytes());
    }

    @ParameterizedTest
    @MethodSource("activeRunStatuses")
    void uploadGateAttachments_happyPath_returnsAttachmentRefs(WorkflowRunStatus runStatus) throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun(runStatus)));
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(buildNodeExec(RUN_ID)));
        when(uploadService.uploadGateFiles(eq("acme"), eq(RUN_ID), eq(NODE_EXEC_ID), anyList()))
                .thenReturn("{\"doc.pdf\":\"acme/runs/" + RUN_ID + "/gate-attachments/" + NODE_EXEC_ID + "/doc.pdf\"}");

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attachmentRefs").exists());

        verify(uploadService).uploadGateFiles(eq("acme"), eq(RUN_ID), eq(NODE_EXEC_ID), anyList());
    }

    @ParameterizedTest
    @EnumSource(value = NodeExecutionStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "awaiting_human")
    void uploadGateAttachments_execNotAwaiting_returns409WithNoUpload(NodeExecutionStatus execStatus) throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun(WorkflowRunStatus.running)));
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(buildNodeExec(RUN_ID, execStatus)));

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isConflict());

        verify(storagePrefixResolver, never()).storagePrefixForRun(any());
        verify(uploadService, never()).uploadGateFiles(any(), any(), any(), anyList());
    }

    @ParameterizedTest
    @MethodSource("terminalRunStatuses")
    void uploadGateAttachments_runClosed_returns409WithNoUploadEvenIfGateAwaits(WorkflowRunStatus runStatus)
            throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun(runStatus)));
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(buildNodeExec(RUN_ID)));

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isConflict());

        verify(storagePrefixResolver, never()).storagePrefixForRun(any());
        verify(uploadService, never()).uploadGateFiles(any(), any(), any(), anyList());
    }

    @Test
    void uploadGateAttachments_cancelledRunWithSkippedExec_returns409WithNoUpload() throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun(WorkflowRunStatus.cancelled)));
        when(nodeExecRepo.findById(NODE_EXEC_ID))
                .thenReturn(Optional.of(buildNodeExec(RUN_ID, NodeExecutionStatus.skipped)));

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isConflict());

        verify(storagePrefixResolver, never()).storagePrefixForRun(any());
        verify(uploadService, never()).uploadGateFiles(any(), any(), any(), anyList());
    }

    @Test
    void uploadGateAttachments_conflictBody_namesCauseAndCarriesNoObjectPath() throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun(WorkflowRunStatus.completed)));
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(buildNodeExec(RUN_ID)));

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("gate-attachments/"))))
                .andExpect(content().string(not(containsString("runs/" + RUN_ID))));
    }

    @Test
    void uploadGateAttachments_runNotFound_returns404() throws Exception {
        when(runRepo.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(multipart("/api/v1/runs/" + UUID.randomUUID() + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadGateAttachments_wrongOrg_returns403() throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun()));
        doThrow(new com.choruskube.core.exception.ForbiddenException("Access denied"))
                .when(authService)
                .checkOrgAccess(anyString(), eq(RUN_ID));

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isForbidden());
    }

    @Test
    void uploadGateAttachments_nodeExecNotFound_returns404() throws Exception {
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun()));
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.empty());

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isNotFound());
    }

    @Test
    void uploadGateAttachments_nodeExecBelongsToDifferentRun_returns404() throws Exception {
        UUID otherRunId = UUID.randomUUID();
        when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(buildRun()));
        // nodeExec belongs to a different run
        when(nodeExecRepo.findById(NODE_EXEC_ID)).thenReturn(Optional.of(buildNodeExec(otherRunId)));

        mockMvc.perform(multipart("/api/v1/runs/" + RUN_ID + "/nodes/" + NODE_EXEC_ID + "/attachments")
                        .file(sampleFile()))
                .andExpect(status().isNotFound());
    }
}
