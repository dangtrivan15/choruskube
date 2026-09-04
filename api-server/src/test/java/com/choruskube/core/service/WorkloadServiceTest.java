package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.credential.AiCredentialResolver;
import com.choruskube.core.dto.CompleteWorkloadRequest;
import com.choruskube.core.dto.CreateWorkloadRequest;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.executor.*;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkloadServiceTest {

    @Mock
    private WorkloadExecutor executor;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private AiCredentialResolver aiCredentialResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkloadService service;

    private static final String DEFAULT_AGENT_IMAGE = "default-agent:latest";
    private static final String DEFAULT_SERVICE_ACCOUNT = "choruskube-agent";
    private static final String TEST_NAMESPACE = "ck-system-test-repo";
    private static final String API_SERVER_URL = "http://api-server:8080";

    @BeforeEach
    void setUp() {
        service = new WorkloadService(
                executor,
                execRepo,
                eventPublisher,
                runRepo,
                snapshotBuilder,
                objectMapper,
                DEFAULT_AGENT_IMAGE,
                DEFAULT_SERVICE_ACCOUNT,
                aiCredentialResolver,
                API_SERVER_URL);
    }

    @Test
    void createWorkload_atomicallyCreatesAndUpdates() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(graphTemplateId);
        workflowRun.setInputs("{}");

        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Test Node",
                    "executor_type": "ai",
                    "image": "test-image:latest",
                    "prompt_template": "do stuff",
                    "timeout_seconds": 300,
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {},
                  "namespace": "%s"
                }
                """.formatted(templateNodeId, TEST_NAMESPACE);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        when(executor.execute(any())).thenReturn(new ExecutionResult("agent-abc12345", "hash123"));
        when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateWorkloadRequest(templateNodeId, Map.of("key", "value"));

        var response = service.createWorkload(runId, nodeExecId, request);

        assertEquals("agent-abc12345", response.executionHandle());
        assertEquals("hash123", response.jobSecretHash());

        // Verify the node execution was atomically updated
        ArgumentCaptor<NodeExecution> captor = ArgumentCaptor.forClass(NodeExecution.class);
        verify(execRepo).save(captor.capture());
        NodeExecution saved = captor.getValue();

        assertEquals(NodeExecutionStatus.running, saved.getStatus());
        assertEquals("agent-abc12345", saved.getPodName());
        assertEquals("hash123", saved.getJobSecretHash());
        assertNotNull(saved.getStartedAt());

        // Verify event was published
        verify(eventPublisher).publishNodeStatusChanged(runId, nodeExecId, "running");

        // Verify resolved params passed to executor
        ArgumentCaptor<ExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(executor).execute(paramsCaptor.capture());
        ExecutionParams params = paramsCaptor.getValue();

        assertEquals(nodeExecId, params.nodeExecutionId());
        assertEquals(runId, params.runId());
        assertEquals(templateNodeId, params.nodeId());
        assertEquals("test-image:latest", params.image());
        assertEquals(Map.of("key", "value"), params.configJson());
        assertFalse(params.enableDocker());
        assertEquals(List.of(), params.nodeCredentials());
        assertEquals(DEFAULT_SERVICE_ACCOUNT, params.identity().name());
    }

    @Test
    void createWorkload_throwsNotFound_whenExecutionMissing() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.empty());

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());

        assertThrows(NotFoundException.class, () -> service.createWorkload(runId, nodeExecId, request));
    }

    @Test
    void createWorkload_usesDefaultImage_whenNodeHasNoImage() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(graphTemplateId);
        workflowRun.setInputs("{}");

        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Test Node",
                    "executor_type": "ai",
                    "image": null,
                    "prompt_template": "do stuff",
                    "timeout_seconds": 300,
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {},
                  "namespace": "%s"
                }
                """.formatted(templateNodeId, TEST_NAMESPACE);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        when(executor.execute(any())).thenReturn(new ExecutionResult("agent-xyz", "hashxyz"));
        when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        service.createWorkload(runId, nodeExecId, request);

        ArgumentCaptor<ExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(executor).execute(paramsCaptor.capture());
        assertEquals(DEFAULT_AGENT_IMAGE, paramsCaptor.getValue().image());
    }

    // Deleted createWorkload_throws_whenSnapshotHasNoNamespace: namespace is no longer part of
    // core's execution path, so the snapshot is no longer required to carry a namespace.

    @Test
    void createWorkload_throwsNotFound_whenTemplateNodeNotInSnapshot() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(graphTemplateId);
        workflowRun.setInputs("{}");

        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Other Node",
                    "executor_type": "ai",
                    "image": "other:latest",
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {}
                }
                """.formatted(UUID.randomUUID());

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());

        assertThrows(NotFoundException.class, () -> service.createWorkload(runId, nodeExecId, request));
    }

    @Test
    void createWorkloadRejectsANodeExecutionFromAnotherRun() {
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();

        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(otherRunId);
        exec.setTemplateNodeId(templateNodeId);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));

        WorkflowRun run = new WorkflowRun();
        run.setId(runId);

        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Test Node",
                    "executor_type": "ai",
                    "image": "test-image:latest",
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {}
                }
                """.formatted(templateNodeId);

        // Lenient and a full valid happy path (not left empty): the guard must reject before
        // any of this is read. Stubbing a complete success path -- rather than an empty one --
        // means a missing guard makes the call SUCCEED, so assertThrows below fails on "no
        // exception thrown" specifically, not on some unrelated collaborator's exception type.
        lenient().when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        lenient().when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshotJson);
        lenient().when(executor.execute(any())).thenReturn(new ExecutionResult("agent-abc12345", "hash123"));
        lenient().when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThrows(
                NotFoundException.class,
                () -> service.createWorkload(runId, nodeExecId, new CreateWorkloadRequest(null, null)));

        // The executor must never be reached: a rejected pairing that still launched a container
        // would leave a pod belonging to neither run.
        verifyNoInteractions(executor);
    }

    @Test
    void cleanupWorkload_delegatesToExecutor() {
        UUID executionId = UUID.randomUUID();

        service.cleanupWorkload(executionId);

        verify(executor).cleanup(executionId);
    }

    @Test
    void getWorkloadLogs_delegatesToExecutor() {
        UUID executionId = UUID.randomUUID();
        when(executor.getLogs(executionId, 100)).thenReturn("log output");

        var response = service.getWorkloadLogs(executionId, 100);

        assertEquals("log output", response.logs());
    }

    @Test
    void cleanupWorkload_runScoped_delegatesWhenExecutionBelongsToRun() {
        UUID runId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var exec = new NodeExecution();
        exec.setId(executionId);
        exec.setWorkflowRunId(runId);
        when(execRepo.findById(executionId)).thenReturn(Optional.of(exec));

        service.cleanupWorkload(runId, executionId);

        verify(executor).cleanup(executionId);
    }

    @Test
    void cleanupWorkload_runScoped_rejectsWhenExecutionBelongsToDifferentRun() {
        UUID runId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var exec = new NodeExecution();
        exec.setId(executionId);
        exec.setWorkflowRunId(UUID.randomUUID()); // a different run
        when(execRepo.findById(executionId)).thenReturn(Optional.of(exec));

        assertThrows(NotFoundException.class, () -> service.cleanupWorkload(runId, executionId));

        verify(executor, never()).cleanup(any());
    }

    @Test
    void cleanupWorkload_runScoped_rejectsWhenExecutionMissing() {
        UUID runId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(execRepo.findById(executionId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.cleanupWorkload(runId, executionId));

        verify(executor, never()).cleanup(any());
    }

    @Test
    void getWorkloadLogs_runScoped_delegatesWhenExecutionBelongsToRun() {
        UUID runId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var exec = new NodeExecution();
        exec.setId(executionId);
        exec.setWorkflowRunId(runId);
        when(execRepo.findById(executionId)).thenReturn(Optional.of(exec));
        when(executor.getLogs(executionId, 100)).thenReturn("log output");

        var response = service.getWorkloadLogs(runId, executionId, 100);

        assertEquals("log output", response.logs());
    }

    @Test
    void getWorkloadLogs_runScoped_rejectsWhenExecutionBelongsToDifferentRun() {
        UUID runId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        var exec = new NodeExecution();
        exec.setId(executionId);
        exec.setWorkflowRunId(UUID.randomUUID()); // a different run
        when(execRepo.findById(executionId)).thenReturn(Optional.of(exec));

        assertThrows(NotFoundException.class, () -> service.getWorkloadLogs(runId, executionId, 100));

        verify(executor, never()).getLogs(any(), anyInt());
    }

    @Test
    void getWorkloadLogs_defaultsTailLines() {
        UUID executionId = UUID.randomUUID();
        when(executor.getLogs(executionId, 50)).thenReturn("log output");

        service.getWorkloadLogs(executionId, 0);

        verify(executor).getLogs(executionId, 50);
    }

    @Test
    void terminateWorkload_delegatesToExecutor() {
        UUID executionId = UUID.randomUUID();

        service.terminateWorkload(executionId);

        verify(executor).terminate(executionId);
    }

    @Test
    void listWorkloads_delegatesToExecutor() {
        var info = new ExecutionInfo(UUID.randomUUID(), UUID.randomUUID(), "agent-abc");

        when(executor.listExecutions()).thenReturn(List.of(info));

        var result = service.listWorkloads();

        assertEquals(1, result.size());
        assertEquals(info, result.getFirst());
    }

    @Test
    void healthCheck_delegatesToExecutor() {
        service.healthCheck();

        verify(executor).healthCheck();
    }

    @Test
    void createWorkload_resolvesEnableDockerFromSnapshot() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(graphTemplateId);
        workflowRun.setInputs("{}");

        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Docker Node",
                    "executor_type": "ai",
                    "image": "test:latest",
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {},
                  "enable_docker": true
                }
                """.formatted(templateNodeId);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        when(executor.execute(any())).thenReturn(new ExecutionResult("agent-docker", "hash"));
        when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        service.createWorkload(runId, nodeExecId, request);

        ArgumentCaptor<ExecutionParams> paramsCaptor = ArgumentCaptor.forClass(ExecutionParams.class);
        verify(executor).execute(paramsCaptor.capture());
        ExecutionParams params = paramsCaptor.getValue();

        assertTrue(params.enableDocker());
    }

    @Test
    void prepareWorkload_returnsCredentialsAndIdentity() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(graphTemplateId);
        workflowRun.setInputs("{}");

        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Test Node",
                    "executor_type": "ai",
                    "image": "test-image:latest",
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {}
                }
                """.formatted(templateNodeId);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        when(aiCredentialResolver.resolveOauthToken(runId)).thenReturn("oauth-secret");

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertEquals("test-image:latest", response.image());
        assertFalse(response.enableDocker());
        assertEquals("oauth-secret", response.claudeOAuthToken());
        assertEquals(
                API_SERVER_URL + "/internal/runs/" + runId + "/node-executions/" + nodeExecId + "/github-token",
                response.githubTokenUrl());
        assertNull(response.registryCredentials());
        assertNull(response.namespace());
        assertEquals(DEFAULT_SERVICE_ACCOUNT, response.serviceAccount());

        // Prepare only resolves inputs; the Worker launches the workload itself.
        verifyNoInteractions(executor);
    }

    @Test
    void prepareWorkload_omitsOauthTokenForScriptNodes() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(graphTemplateId);
        workflowRun.setInputs("{}");

        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Test Node",
                    "executor_type": "ai",
                    "image": "test-image:latest",
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {}
                }
                """.formatted(templateNodeId);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);

        // executor_type=script travels through configJson (the request), not the snapshot node.
        var request = new CreateWorkloadRequest(templateNodeId, Map.of("executor_type", "script"));
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertNull(response.claudeOAuthToken());
        verifyNoInteractions(aiCredentialResolver);
    }

    @Test
    void prepareWorkloadRejectsANodeExecutionFromAnotherRun() {
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();

        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(otherRunId);
        exec.setTemplateNodeId(templateNodeId);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));

        assertThrows(
                NotFoundException.class,
                () -> service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(null, null)));

        verifyNoInteractions(runRepo, aiCredentialResolver);
    }

    @Test
    void completeWorkload_recordsPodNameHashAndStatus() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();

        var exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setStatus(NodeExecutionStatus.pending);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        when(execRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeWorkload(runId, nodeExecId, new CompleteWorkloadRequest("agent-abc12345", "deadbeef"));

        ArgumentCaptor<NodeExecution> captor = ArgumentCaptor.forClass(NodeExecution.class);
        verify(execRepo).save(captor.capture());
        NodeExecution saved = captor.getValue();

        assertEquals("agent-abc12345", saved.getPodName());
        assertEquals("deadbeef", saved.getJobSecretHash());
        assertEquals(NodeExecutionStatus.running, saved.getStatus());
        assertNotNull(saved.getStartedAt());

        verify(eventPublisher).publishNodeStatusChanged(runId, nodeExecId, "running");
    }

    @Test
    void completeWorkload_throwsNotFound_whenExecutionMissing() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.empty());

        assertThrows(
                NotFoundException.class,
                () -> service.completeWorkload(runId, nodeExecId, new CompleteWorkloadRequest("p", "h")));

        verify(execRepo, never()).save(any());
    }

    @Test
    void completeWorkloadRejectsANodeExecutionFromAnotherRun() {
        UUID runId = UUID.randomUUID();
        UUID otherRunId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();

        var exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(otherRunId);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));

        assertThrows(
                NotFoundException.class,
                () -> service.completeWorkload(runId, nodeExecId, new CompleteWorkloadRequest("p", "h")));

        verify(execRepo, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }
}
