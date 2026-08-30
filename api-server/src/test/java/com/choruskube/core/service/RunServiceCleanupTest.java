package com.choruskube.core.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for RunService covering workload cleanup on cancel, pause, and retry.
 * Verifies that cleanupWorkload() (full DELETE) is used instead of terminateWorkload()
 * (patch-only) for all three paths, and that cleanup is scoped to running nodes only.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceCleanupTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private TemplateEdgeRepository edgeRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private TemplateNodeRepository templateNodeRepo;

    @Mock
    private GraphValidationService validationService;

    @Mock
    private ExecutionLogRepository executionLogRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private WorkloadService workloadService;

    @Mock
    private AuditSink auditService;

    @Mock
    private WorkflowStub workflowStub;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                workflowClient,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                objectMapper,
                eventPublisher,
                gitRepoRepo,
                workloadService,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(),
                Optional.empty(),
                null,
                auditService,
                null, // storagePrefixResolver — not invoked by cancel/pause/retry
                null,
                null,
                null,
                null,
                null,
                null,
                null, // storyRepo
                null, // epicRepo
                null,
                null,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null,
                null,
                null, // nodeExecutionClaimService — unused (signalHumanDecision not exercised)
                null); // escalationContextResolver — unused (escalation not exercised)
        // AuthorizationService is constructed with authEnabled=false, so
        // checkOrgAccess() is a no-op and storagePrefixResolver is not invoked — no stub needed.
    }

    private WorkflowRun stubRun(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(status);
        run.setExternalRunId("ext-wf-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(workflowClient.newUntypedWorkflowStub("ext-wf-id")).thenReturn(workflowStub);
        return run;
    }

    private NodeExecution makeExec(NodeExecutionStatus status) {
        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setStatus(status);
        // Note: execRepo.findById stub is only registered in tests that actually need it
        // (retryNode). cancel/pause use findByWorkflowRunId, so findById is not called.
        return exec;
    }

    // -----------------------------------------------------------------------
    // cancelRun: must call cleanupWorkload, never terminateWorkload
    // -----------------------------------------------------------------------

    @Test
    void cancelRun_callsCleanupNotTerminate() throws Exception {
        stubRun(WorkflowRunStatus.running);

        NodeExecution running = makeExec(NodeExecutionStatus.running);
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(running));
        when(execRepo.save(any())).thenReturn(running);

        service.cancelRun(runId);

        verify(workloadService).cleanupWorkload(running.getId());
        verify(workloadService, never()).terminateWorkload(any());
    }

    // -----------------------------------------------------------------------
    // pauseRun: must cleanup running nodes only, not pending/other
    // -----------------------------------------------------------------------

    @Test
    void pauseRun_cleansUpRunningExecutionsOnly() throws Exception {
        stubRun(WorkflowRunStatus.running);

        NodeExecution running = makeExec(NodeExecutionStatus.running);
        NodeExecution pending = makeExec(NodeExecutionStatus.pending);
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(running, pending));

        service.pauseRun(runId);

        // cleanup called exactly once — for the running node
        verify(workloadService).cleanupWorkload(running.getId());
        // cleanup NOT called for the pending node
        verify(workloadService, never()).cleanupWorkload(pending.getId());
        verify(workloadService, never()).terminateWorkload(any());
    }

    // -----------------------------------------------------------------------
    // retryNode: must call cleanupWorkload, never terminateWorkload
    // -----------------------------------------------------------------------

    @Test
    void retryNode_callsCleanupNotTerminate() throws Exception {
        WorkflowRun run = stubRun(WorkflowRunStatus.awaiting_retry);

        NodeExecution exec = makeExec(NodeExecutionStatus.failed);
        UUID templateNodeId = UUID.randomUUID();
        exec.setTemplateNodeId(templateNodeId);
        // retryNode calls execRepo.findById(nodeExecId) directly
        when(execRepo.findById(exec.getId())).thenReturn(Optional.of(exec));

        service.retryNode(runId, exec.getId());

        verify(workloadService).cleanupWorkload(exec.getId());
        verify(workloadService, never()).terminateWorkload(any());
    }
}
