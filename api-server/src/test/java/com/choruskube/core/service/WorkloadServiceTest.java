package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CompleteWorkloadRequest;
import com.choruskube.core.dto.CreateWorkloadRequest;
import com.choruskube.core.dto.PrepareWorkloadResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.executor.WorkloadAgentCredentialResolver;
import com.choruskube.core.executor.WorkloadAgentCredentials;
import com.choruskube.core.executor.WorkloadMemoryCeilingPolicy;
import com.choruskube.core.executor.WorkloadNamespaceResolver;
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
    private WorkloadAgentCredentialResolver agentCredentialResolver;

    @Mock
    private WorkloadNamespaceResolver namespaceResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkloadService service;

    private static final String DEFAULT_AGENT_IMAGE = "default-agent:latest";
    private static final String DEFAULT_SERVICE_ACCOUNT = "choruskube-agent";
    private static final String API_SERVER_URL = "http://api-server:8080";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<WorkloadNamespaceResolver> namespaceResolverProvider = mock(ObjectProvider.class);
        when(namespaceResolverProvider.getIfAvailable(any())).thenReturn(namespaceResolver);
        ObjectProvider<WorkloadAgentCredentialResolver> agentCredentialResolverProvider = mock(ObjectProvider.class);
        when(agentCredentialResolverProvider.getIfAvailable(any())).thenReturn(agentCredentialResolver);

        service = serviceWith(
                namespaceResolverProvider, agentCredentialResolverProvider, noCeilingPolicyRegisteredProvider());
    }

    private WorkloadService serviceWith(
            ObjectProvider<WorkloadNamespaceResolver> namespaceResolverProvider,
            ObjectProvider<WorkloadAgentCredentialResolver> agentCredentialResolverProvider,
            ObjectProvider<WorkloadMemoryCeilingPolicy> memoryCeilingPolicyProvider) {
        return new WorkloadService(
                execRepo,
                eventPublisher,
                runRepo,
                snapshotBuilder,
                objectMapper,
                DEFAULT_AGENT_IMAGE,
                DEFAULT_SERVICE_ACCOUNT,
                API_SERVER_URL,
                namespaceResolverProvider,
                agentCredentialResolverProvider,
                memoryCeilingPolicyProvider);
    }

    /**
     * Constructs a provider that mimics Spring's real {@code ObjectProvider.getIfAvailable}: no
     * bean exists, so it invokes the fallback supplier instead of returning a stand-in mock. Used
     * to prove {@link WorkloadService} actually falls through to the real default resolver, not
     * just to whatever a test double returns.
     */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkloadNamespaceResolver> noNamespaceBeanRegisteredProvider() {
        ObjectProvider<WorkloadNamespaceResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<WorkloadNamespaceResolver> fallback = invocation.getArgument(0);
            return fallback.get();
        });
        return provider;
    }

    /** As {@link #noNamespaceBeanRegisteredProvider()}, for the memory-ceiling seam's real default. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkloadMemoryCeilingPolicy> noCeilingPolicyRegisteredProvider() {
        ObjectProvider<WorkloadMemoryCeilingPolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<WorkloadMemoryCeilingPolicy> fallback = invocation.getArgument(0);
            return fallback.get();
        });
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkloadMemoryCeilingPolicy> ceilingPolicyProvider(
            WorkloadMemoryCeilingPolicy policy) {
        ObjectProvider<WorkloadMemoryCeilingPolicy> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(policy);
        return provider;
    }

    /** As {@link #noNamespaceBeanRegisteredProvider()}, for the agent-credential seam's real default. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<WorkloadAgentCredentialResolver> noAgentCredentialBeanRegisteredProvider() {
        ObjectProvider<WorkloadAgentCredentialResolver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenAnswer(invocation -> {
            java.util.function.Supplier<WorkloadAgentCredentialResolver> fallback = invocation.getArgument(0);
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
        when(agentCredentialResolver.resolve(runId, true))
                .thenReturn(new WorkloadAgentCredentials("oauth-secret", null));

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
    }

    /**
     * Anti-vacuity guard: with no {@code WorkloadNamespaceResolver} bean registered,
     * {@code prepareWorkload} must fall through to the real {@link
     * com.choruskube.core.executor.NoWorkloadNamespaceResolver} (via {@code
     * ObjectProvider.getIfAvailable}'s fallback supplier, not a test double standing in for one),
     * which returns {@code ""} — mapped to a null (namespace-less) launch.
     */
    @Test
    void prepareWorkload_returnsNullNamespace_withTheDefaultSeam() {
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
                  "inputs": {},
                  "enable_docker": true
                }
                """.formatted(templateNodeId);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);

        WorkloadService serviceWithoutNamespaceBean = new WorkloadService(
                execRepo,
                eventPublisher,
                runRepo,
                snapshotBuilder,
                objectMapper,
                DEFAULT_AGENT_IMAGE,
                DEFAULT_SERVICE_ACCOUNT,
                API_SERVER_URL,
                noNamespaceBeanRegisteredProvider(),
                noAgentCredentialBeanRegisteredProvider(),
                noCeilingPolicyRegisteredProvider());

        var response = serviceWithoutNamespaceBean.prepareWorkload(
                runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        // The real NoWorkloadNamespaceResolver returns "", which prepare maps to a null
        // (namespace-less) launch.
        assertNull(response.namespace());
    }

    /**
     * This is the anti-vacuity guard for the agent-credential seam: with no {@code
     * WorkloadAgentCredentialResolver} bean registered, {@code prepareWorkload} must fall through
     * to the real {@link com.choruskube.core.executor.NoWorkloadAgentCredentialResolver} (via
     * {@code ObjectProvider.getIfAvailable}'s fallback supplier, not a test double standing in for
     * one) and return both a null token and null registry credentials, without throwing.
     */
    @Test
    void prepareWorkload_withNoResolverRegistered_attachesNoAgentCredentials() {
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

        WorkloadService serviceWithoutCredentialBean = new WorkloadService(
                execRepo,
                eventPublisher,
                runRepo,
                snapshotBuilder,
                objectMapper,
                DEFAULT_AGENT_IMAGE,
                DEFAULT_SERVICE_ACCOUNT,
                API_SERVER_URL,
                noNamespaceBeanRegisteredProvider(),
                noAgentCredentialBeanRegisteredProvider(),
                noCeilingPolicyRegisteredProvider());

        var response = serviceWithoutCredentialBean.prepareWorkload(
                runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        assertNull(response.claudeOAuthToken());
        assertNull(response.registryCredentials());
    }

    @Test
    void prepareWorkload_returnsWhatTheResolverAttaches() {
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
        when(agentCredentialResolver.resolve(runId, true))
                .thenReturn(new WorkloadAgentCredentials(
                        "oauth-secret",
                        new PrepareWorkloadResponse.RegistryCredentialsDto(
                                "registry.example.test", "svc-account", "s3cr3t")));

        var response = service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        assertEquals("oauth-secret", response.claudeOAuthToken());
        assertEquals(
                new PrepareWorkloadResponse.RegistryCredentialsDto("registry.example.test", "svc-account", "s3cr3t"),
                response.registryCredentials());
        verify(agentCredentialResolver).resolve(runId, true);
    }

    /**
     * A resolver that throws fails preparation outright — it is not degraded to an anonymous
     * launch the way an unresolvable namespace or memory policy is, because a thrown credential
     * lookup leaves no safe default to attach instead.
     */
    @Test
    void prepareWorkload_resolverFailure_failsPrepare() {
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
        when(agentCredentialResolver.resolve(runId, true)).thenThrow(new IllegalStateException("resolution failed"));

        assertThrows(
                IllegalStateException.class,
                () -> service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of())));
    }

    @Test
    void prepareWorkload_returnsNamespace_fromTheResolver() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(UUID.randomUUID());
        workflowRun.setInputs("{}");

        // enable_docker false: the launch namespace is resolved for every node.
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
                  "inputs": {},
                  "enable_docker": false
                }
                """.formatted(templateNodeId);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        when(agentCredentialResolver.resolve(runId, true))
                .thenReturn(new WorkloadAgentCredentials("oauth-secret", null));
        when(namespaceResolver.resolve(runId)).thenReturn("choruskube-org-acme");

        var response = service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        // The K8s executor launches into this and later addresses the same resources by name in it,
        // so the namespace prepare hands back must be exactly what the resolver produced.
        assertEquals("choruskube-org-acme", response.namespace());
        verify(namespaceResolver).resolve(runId);
    }

    @Test
    void prepareWorkload_returnsNullNamespace_whenTenantUnresolvable() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);

        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(UUID.randomUUID());
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
                  "inputs": {},
                  "enable_docker": false
                }
                """.formatted(templateNodeId);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        when(agentCredentialResolver.resolve(runId, true))
                .thenReturn(new WorkloadAgentCredentials("oauth-secret", null));
        // A run with no resolvable tenant (the Docker/e2e case) — prepare must degrade to a
        // namespace-less launch, not propagate the failure and refuse to prepare the workload.
        when(namespaceResolver.resolve(runId)).thenThrow(new IllegalStateException("no ownership row"));

        var response = service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        assertNull(response.namespace());
    }

    @Test
    void prepareWorkload_scriptNode_asksForNoClaudeToken() {
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
        when(agentCredentialResolver.resolve(runId, false)).thenReturn(WorkloadAgentCredentials.NONE);

        var request = new CreateWorkloadRequest(templateNodeId, Map.of("executor_type", "script"));
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertNull(response.claudeOAuthToken());
        verify(agentCredentialResolver).resolve(runId, false);
    }

    @Test
    void prepareWorkload_missingExecutorType_asksForAClaudeToken() {
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
        when(agentCredentialResolver.resolve(runId, true)).thenReturn(new WorkloadAgentCredentials("token", null));

        // No "executor_type" key in the request's configJson at all — defaults to "ai".
        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        service.prepareWorkload(runId, nodeExecId, request);

        verify(agentCredentialResolver).resolve(runId, true);
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
        when(agentCredentialResolver.resolve(runId, true)).thenReturn(new WorkloadAgentCredentials("token", null));

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

        verifyNoInteractions(runRepo, agentCredentialResolver);
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
        when(agentCredentialResolver.resolve(runId, true)).thenReturn(new WorkloadAgentCredentials("token", null));

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertTrue(response.enableDocker());
    }

    @Test
    void prepareWorkload_returnsDindImage_whenSnapshotHasOne() {
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
                  "enable_docker": true,
                  "dind_image": "registry.example/foo-dind:latest",
                  "agent_memory_request": "1792Mi",
                  "dind_memory_request": "512Mi"
                }
                """.formatted(templateNodeId);

        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        when(agentCredentialResolver.resolve(runId, true)).thenReturn(new WorkloadAgentCredentials("token", null));

        var request = new CreateWorkloadRequest(templateNodeId, Map.of());
        var response = service.prepareWorkload(runId, nodeExecId, request);

        assertEquals("registry.example/foo-dind:latest", response.dindImage());
        assertEquals("1792Mi", response.agentMemoryRequest());
        assertEquals("512Mi", response.dindMemoryRequest());
        assertTrue(response.memoryCeilingExempt(), "with no policy bean, a single-tenant run is exempt");
    }

    /**
     * A deployment's policy decides the exemption, and a policy that throws caps the run: an
     * unresolvable run must never lift the ceiling.
     */
    @Test
    void prepareWorkload_memoryCeilingExemptFollowsPolicyAndCapsWhenItThrows() {
        UUID runId = UUID.randomUUID();
        UUID nodeExecId = UUID.randomUUID();
        UUID templateNodeId = UUID.randomUUID();

        var nodeExec = new NodeExecution();
        nodeExec.setId(nodeExecId);
        nodeExec.setWorkflowRunId(runId);
        nodeExec.setTemplateNodeId(templateNodeId);
        nodeExec.setStatus(NodeExecutionStatus.pending);
        var workflowRun = new WorkflowRun();
        workflowRun.setId(runId);
        workflowRun.setGraphTemplateId(UUID.randomUUID());
        workflowRun.setInputs("{}");
        String snapshotJson = """
                {
                  "nodes": [{
                    "template_node_id": "%s",
                    "label": "Node",
                    "executor_type": "ai",
                    "image": "test:latest",
                    "secrets": [],
                    "is_entrypoint": true
                  }],
                  "edges": [],
                  "inputs": {},
                  "agent_memory_request": "8Gi"
                }
                """.formatted(templateNodeId);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(nodeExec));
        when(runRepo.findById(runId)).thenReturn(Optional.of(workflowRun));
        when(snapshotBuilder.buildSnapshotForRun(workflowRun)).thenReturn(snapshotJson);
        // Both variants below use noAgentCredentialBeanRegisteredProvider(), the real default —
        // not the shared agentCredentialResolver mock — so there is nothing to stub on it here.
        var request = new CreateWorkloadRequest(templateNodeId, Map.of());

        WorkloadService capping = serviceWith(
                noNamespaceBeanRegisteredProvider(),
                noAgentCredentialBeanRegisteredProvider(),
                ceilingPolicyProvider(id -> false));
        assertFalse(capping.prepareWorkload(runId, nodeExecId, request).memoryCeilingExempt());

        WorkloadService throwing = serviceWith(
                noNamespaceBeanRegisteredProvider(),
                noAgentCredentialBeanRegisteredProvider(),
                ceilingPolicyProvider(id -> {
                    throw new IllegalStateException("no ownership row");
                }));
        assertFalse(throwing.prepareWorkload(runId, nodeExecId, request).memoryCeilingExempt());
    }

    @Test
    void prepareWorkload_returnsNullDindImage_whenSnapshotHasNone() {
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
        when(agentCredentialResolver.resolve(runId, true))
                .thenReturn(new WorkloadAgentCredentials("oauth-secret", null));

        var response = service.prepareWorkload(runId, nodeExecId, new CreateWorkloadRequest(templateNodeId, Map.of()));

        assertNull(response.dindImage());
        assertNull(response.agentMemoryRequest());
        assertNull(response.dindMemoryRequest());
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
