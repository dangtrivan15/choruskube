package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.credential.AiCredentialResolver;
import com.choruskube.core.dto.CompleteWorkloadRequest;
import com.choruskube.core.dto.CreateWorkloadRequest;
import com.choruskube.core.dto.PrepareWorkloadResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.executor.NoRegistryMirrorResolver;
import com.choruskube.core.executor.RegistryMirror;
import com.choruskube.core.executor.WorkloadRegistryMirrorResolver;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class WorkloadServiceTest {

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

    @Mock
    private WorkloadRegistryMirrorResolver registryMirrorResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkloadService service;

    private static final String DEFAULT_AGENT_IMAGE = "default-agent:latest";
    private static final String DEFAULT_SERVICE_ACCOUNT = "choruskube-agent";
    private static final String API_SERVER_URL = "http://api-server:8080";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<WorkloadRegistryMirrorResolver> registryMirrorResolverProvider = mock(ObjectProvider.class);
        when(registryMirrorResolverProvider.getIfAvailable(any())).thenReturn(registryMirrorResolver);

        service = new WorkloadService(
                execRepo,
                eventPublisher,
                runRepo,
                snapshotBuilder,
                objectMapper,
                DEFAULT_AGENT_IMAGE,
                DEFAULT_SERVICE_ACCOUNT,
                aiCredentialResolver,
                API_SERVER_URL,
                registryMirrorResolverProvider);
    }

    /**
     * Constructs a provider that mimics Spring's real {@code ObjectProvider.getIfAvailable}: no
     * bean exists, so it invokes the fallback supplier instead of returning a stand-in mock. Used
     * to prove {@link WorkloadService} actually falls through to the real default resolver, not
     * just to whatever a test double returns.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkloadRegistryMirrorResolver> noBeanRegisteredProvider() {
        ObjectProvider<WorkloadRegistryMirrorResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<WorkloadRegistryMirrorResolver> fallback = invocation.getArgument(0);
            return fallback.get();
        });
        return provider;
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
        assertNull(response.registryMirror());
    }

    /**
     * Anti-vacuity guard: with no {@code WorkloadRegistryMirrorResolver} bean registered,
     * {@code prepareWorkload} must fall through to the real {@link NoRegistryMirrorResolver} (via
     * {@code ObjectProvider.getIfAvailable}'s fallback supplier, not a test double standing in for
     * one) and return a null {@code registryMirror}.
     */
    @Test
    void prepareWorkload_returnsNullRegistryMirror_withTheDefaultSeam() {
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

        WorkloadService serviceWithoutMirrorBean = new WorkloadService(
                execRepo,
                eventPublisher,
                runRepo,
                snapshotBuilder,
                objectMapper,
                DEFAULT_AGENT_IMAGE,
                DEFAULT_SERVICE_ACCOUNT,
                aiCredentialResolver,
                API_SERVER_URL,
                noBeanRegisteredProvider());

        var response = serviceWithoutMirrorBean.prepareWorkload(
                runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        assertNull(response.registryMirror());
    }

    @Test
    void prepareWorkload_returnsRegistryMirror_whenResolverProvidesOne() {
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
        when(registryMirrorResolver.resolve(runId))
                .thenReturn(new RegistryMirror(
                        "mirror.internal.test:5000", "mirror.internal.test:5001", "http://mirror.internal.test:8081"));

        var response = service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        assertEquals(
                new PrepareWorkloadResponse.RegistryMirrorDto(
                        "mirror.internal.test:5000", "mirror.internal.test:5001", "http://mirror.internal.test:8081"),
                response.registryMirror());
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

        var request = new CreateWorkloadRequest(templateNodeId, Map.of("executor_type", "script"));
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertNull(response.claudeOAuthToken());
        verifyNoInteractions(aiCredentialResolver);
    }

    @Test
    void prepareWorkload_throwsNotFound_whenExecutionMissing() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.empty());

        assertThrows(
                NotFoundException.class,
                () -> service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of())));
    }

    @Test
    void prepareWorkload_usesDefaultImage_whenNodeHasNoImage() {
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
        when(aiCredentialResolver.resolveOauthToken(runId)).thenReturn("token");

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertEquals(DEFAULT_AGENT_IMAGE, response.image());
    }

    @Test
    void prepareWorkload_throwsNotFound_whenTemplateNodeNotInSnapshot() {
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

        assertThrows(
                NotFoundException.class,
                () -> service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of())));
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
    void prepareWorkload_resolvesEnableDockerFromSnapshot() {
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
        when(aiCredentialResolver.resolveOauthToken(runId)).thenReturn("token");

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertTrue(response.enableDocker());
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
