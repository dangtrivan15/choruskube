package com.choruskube.core.service;

import com.choruskube.core.credential.AiCredentialResolver;
import com.choruskube.core.dto.CompleteWorkloadRequest;
import com.choruskube.core.dto.CreateWorkloadRequest;
import com.choruskube.core.dto.PrepareWorkloadResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.executor.*;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer for workload execution operations.
 *
 * <p>The Worker binary now owns the full container lifecycle (launch, cleanup, logs,
 * terminate). This service resolves what a Worker needs to launch a workload
 * ({@link #prepareWorkload}) and records the result ({@link #completeWorkload}).
 */
@Service
public class WorkloadService {

    private static final Logger log = LoggerFactory.getLogger(WorkloadService.class);

    private final NodeExecutionRepository execRepo;
    private final RunEventPublisher eventPublisher;
    private final WorkflowRunRepository runRepo;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final ObjectMapper objectMapper;
    private final String defaultAgentImage;
    private final String defaultServiceAccount;
    private final AiCredentialResolver aiCredentialResolver;
    private final String apiServerUrl;
    private final WorkloadRegistryMirrorResolver registryMirrorResolver;

    public WorkloadService(
            NodeExecutionRepository execRepo,
            RunEventPublisher eventPublisher,
            WorkflowRunRepository runRepo,
            GraphSnapshotBuilder snapshotBuilder,
            ObjectMapper objectMapper,
            @Qualifier("executorDefaultAgentImage") String defaultAgentImage,
            @Value("${executor.k8s.agent-service-account:choruskube-agent}") String defaultServiceAccount,
            AiCredentialResolver aiCredentialResolver,
            @Qualifier("executorApiServerUrl") String apiServerUrl,
            ObjectProvider<WorkloadRegistryMirrorResolver> registryMirrorResolverProvider) {
        this.execRepo = execRepo;
        this.eventPublisher = eventPublisher;
        this.runRepo = runRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.objectMapper = objectMapper;
        this.defaultAgentImage = defaultAgentImage;
        this.defaultServiceAccount = defaultServiceAccount;
        this.aiCredentialResolver = aiCredentialResolver;
        this.apiServerUrl = apiServerUrl;
        this.registryMirrorResolver = registryMirrorResolverProvider.getIfAvailable(NoRegistryMirrorResolver::new);
    }

    /**
     * Resolves what a Worker needs to launch this workload itself: image, credentials, and
     * identity. The api-server is the only party with DB access, so it resolves inputs here
     * instead of launching a container itself.
     */
    @Transactional(readOnly = true)
    public PrepareWorkloadResponse prepareWorkload(UUID runId, UUID nodeExecId, CreateWorkloadRequest req) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        NodeExecutionUtil.requireInRun(exec, runId);

        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));

        ExecutionParams params = resolveExecutionParams(nodeExecId, run, exec.getTemplateNodeId(), req);

        String claudeOAuthToken = needsOauthToken(params) ? aiCredentialResolver.resolveOauthToken(runId) : null;
        String githubTokenUrl =
                apiServerUrl + "/internal/runs/" + runId + "/node-executions/" + nodeExecId + "/github-token";
        // Only the executor's DinD path reads registryMirror, gated on enableDocker itself --
        // consulting the resolver here for every other prepare would cost a real implementation a
        // DB round trip and could fail a launch that never needed a mirror.
        RegistryMirror mirror = params.enableDocker() ? registryMirrorResolver.resolve(runId) : null;

        return new PrepareWorkloadResponse(
                params.image(),
                params.enableDocker(),
                claudeOAuthToken,
                githubTokenUrl,
                null,
                null,
                params.identity() != null ? params.identity().name() : null,
                mirror == null
                        ? null
                        : new PrepareWorkloadResponse.RegistryMirrorDto(
                                mirror.mirror(), mirror.buildCache(), mirror.depProxyBase()));
    }

    /**
     * Records what a Worker launched on its own and transitions the execution to running.
     */
    @Transactional
    public void completeWorkload(UUID runId, UUID nodeExecId, CompleteWorkloadRequest req) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        NodeExecutionUtil.requireInRun(exec, runId);

        exec.setStatus(NodeExecutionStatus.running);
        exec.setPodName(req.podName());
        exec.setJobSecretHash(req.jobSecretHash());
        if (exec.getStartedAt() == null) {
            exec.setStartedAt(Instant.now());
        }
        execRepo.save(exec);

        eventPublisher.publishNodeStatusChanged(runId, nodeExecId, "running");
        log.info("Completed workload for execution {}: pod={}", nodeExecId, req.podName());
    }

    private static boolean needsOauthToken(ExecutionParams params) {
        Object raw = params.configJson() != null ? params.configJson().getOrDefault("executor_type", "ai") : "ai";
        return !"script".equalsIgnoreCase(String.valueOf(raw));
    }

    private ExecutionParams resolveExecutionParams(
            UUID nodeExecId, WorkflowRun run, UUID templateNodeId, CreateWorkloadRequest req) {
        UUID runId = run.getId();

        String snapshotJson;
        try {
            snapshotJson = snapshotBuilder.buildSnapshotForRun(run);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build snapshot for run " + runId, e);
        }

        JsonNode snapshot;
        try {
            snapshot = objectMapper.readTree(snapshotJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse snapshot JSON", e);
        }

        JsonNode targetNode = null;
        for (JsonNode node : snapshot.path("nodes")) {
            if (templateNodeId.toString().equals(node.path("template_node_id").asText())) {
                targetNode = node;
                break;
            }
        }
        if (targetNode == null) {
            throw new NotFoundException("Template node " + templateNodeId + " not found in snapshot");
        }

        String image = targetNode.path("image").asText(null);
        if (image == null || image.isBlank()) {
            JsonNode inputs = snapshot.path("inputs");
            if (inputs.has("agent_image")
                    && !inputs.path("agent_image").asText("").isBlank()) {
                image = inputs.path("agent_image").asText();
            } else {
                image = defaultAgentImage;
            }
        }

        boolean enableDocker = snapshot.path("enable_docker").asBoolean(false);

        List<CredentialSpec> nodeCredentials = List.of();
        if (targetNode.has("secrets") && targetNode.get("secrets").isArray()) {
            List<CredentialSpec> creds = new ArrayList<>();
            for (JsonNode s : targetNode.get("secrets")) {
                creds.add(new CredentialSpec(
                        s.path("name").asText(),
                        s.path("mountType").asText("env"),
                        s.path("mountPath").asText(null),
                        s.has("readOnly") ? s.get("readOnly").asBoolean(true) : null));
            }
            nodeCredentials = creds;
        }

        IdentitySpec identity = new IdentitySpec(defaultServiceAccount, 1000, false);

        return new ExecutionParams(
                nodeExecId, runId, templateNodeId, image, req.configJson(), enableDocker, nodeCredentials, identity);
    }
}
