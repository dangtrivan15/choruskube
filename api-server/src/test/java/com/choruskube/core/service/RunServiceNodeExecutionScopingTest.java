package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that run-scoped node-execution endpoints reject a nodeExecId belonging to a different
 * run, before performing any mutation or side effect.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceNodeExecutionScopingTest {

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
    private NodeExecutionClaimService claimService;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID foreignExecId = UUID.randomUUID();

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
                Optional.empty(), // quotaService
                null, // placements
                null, // workflowClients
                null, // usageSink
                auditService, // auditSink
                null, // storagePrefixResolver
                null, // runPullRequestService
                null, // softwareProjectRepo
                null, // repoGroupMemberRepo
                null, // credentialPreflightChecker
                null, // uploadService
                null, // taskRepo
                null, // storyRepo
                null, // epicRepo
                null, // artifactResolutionService
                null, // applicationEventPublisher
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null, // roadmapCandidateMaterializer
                null, // roadmapCandidatesArtifactResolver
                claimService,
                null); // escalationContextResolver
    }

    private WorkflowRun stubRun(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(status);
        run.setExternalRunId("ext-wf-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    /** A node execution that belongs to some OTHER run. */
    private NodeExecution stubForeignExec(NodeExecutionStatus status) {
        NodeExecution exec = new NodeExecution();
        exec.setId(foreignExecId);
        exec.setWorkflowRunId(UUID.randomUUID());
        exec.setStatus(status);
        when(execRepo.findById(foreignExecId)).thenReturn(Optional.of(exec));
        return exec;
    }

    @Test
    void signalHumanDecision_execBelongsToDifferentRun_throwsNotFoundAndDoesNotClaim() {
        stubRun(WorkflowRunStatus.awaiting_human);
        stubForeignExec(NodeExecutionStatus.awaiting_human);

        assertThrows(
                NotFoundException.class,
                () -> service.signalHumanDecision(
                        runId, foreignExecId, new SignalRequest("approved", null, null, null)));

        verify(claimService, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void retryNode_execBelongsToDifferentRun_throwsNotFoundAndDoesNotTerminateWorkload() {
        stubRun(WorkflowRunStatus.awaiting_retry);
        stubForeignExec(NodeExecutionStatus.failed);

        assertThrows(NotFoundException.class, () -> service.retryNode(runId, foreignExecId));

        verifyNoInteractions(workloadService);
    }
}
