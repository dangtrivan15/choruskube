package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkloadService service;

    private static final String DEFAULT_AGENT_IMAGE = "default-agent:latest";
    private static final String DEFAULT_SERVICE_ACCOUNT = "choruskube-agent";
    private static final String TEST_NAMESPACE = "ck-system-test-repo";

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
                DEFAULT_SERVICE_ACCOUNT);
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
}
