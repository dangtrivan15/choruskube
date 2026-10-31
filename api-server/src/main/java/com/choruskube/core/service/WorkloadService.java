package com.choruskube.core.service;

import com.choruskube.core.dto.CompleteWorkloadRequest;
import com.choruskube.core.dto.PrepareWorkloadRequest;
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
 * Resolves what a Worker needs to launch a workload ({@link #prepareWorkload}) and records what it
 * launched ({@link #completeWorkload}). The Worker owns the container lifecycle.
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
    private final WorkloadNamespaceResolver namespaceResolver;
    private final WorkloadAgentCredentialResolver agentCredentialResolver;
    private final WorkloadMemoryCeilingPolicy memoryCeilingPolicy;

    public WorkloadService(
            NodeExecutionRepository execRepo,
            RunEventPublisher eventPublisher,
            WorkflowRunRepository runRepo,
            GraphSnapshotBuilder snapshotBuilder,
            ObjectMapper objectMapper,
            @Qualifier("executorDefaultAgentImage") String defaultAgentImage,
            @Value("${executor.k8s.agent-service-account:choruskube-agent}") String defaultServiceAccount,
            ObjectProvider<WorkloadNamespaceResolver> namespaceResolverProvider,
            ObjectProvider<WorkloadAgentCredentialResolver> agentCredentialResolverProvider,
            ObjectProvider<WorkloadMemoryCeilingPolicy> memoryCeilingPolicyProvider) {
        this.execRepo = execRepo;
        this.eventPublisher = eventPublisher;
        this.runRepo = runRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.objectMapper = objectMapper;
        this.defaultAgentImage = defaultAgentImage;
        this.defaultServiceAccount = defaultServiceAccount;
        this.namespaceResolver = namespaceResolverProvider.getIfAvailable(NoWorkloadNamespaceResolver::new);
        // getIfAvailable, not getIfUnique: two registered resolvers must fail startup, where
        // getIfUnique would silently fall back to attaching nothing.
        this.agentCredentialResolver =
                agentCredentialResolverProvider.getIfAvailable(NoWorkloadAgentCredentialResolver::new);
        this.memoryCeilingPolicy = memoryCeilingPolicyProvider.getIfAvailable(ExemptMemoryCeilingPolicy::new);
    }

    /**
     * Resolves what a Worker needs to launch this workload: image, identity, and whatever the
     * deployment's {@link WorkloadAgentCredentialResolver} seam attaches — agent credentials never
     * come from anywhere else here.
     */
    @Transactional(readOnly = true)
    public PrepareWorkloadResponse prepareWorkload(UUID runId, UUID nodeExecId, PrepareWorkloadRequest req) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        NodeExecutionUtil.requireInRun(exec, runId);

        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));

        ExecutionParams params = resolveExecutionParams(nodeExecId, run, exec.getTemplateNodeId(), req);

        WorkloadAgentCredentials agent = agentCredentialResolver.resolve(runId, runsClaude(params));

        // Launch namespace and the teardown namespace (resolved later through this same seam by the
        // /worker node-exec GET) must agree; one resolver for both is what guarantees it.
        String namespace = resolveNamespaceOrNull(runId);

        return new PrepareWorkloadResponse(
                params.image(),
                params.enableDocker(),
                params.dindImage(),
                params.agentMemoryRequest(),
                params.dindMemoryRequest(),
                memoryCeilingExempt(runId),
                agent.claudeOAuthToken(),
                agent.registryCredentials(),
                namespace,
                params.identity() != null ? params.identity().name() : null);
    }

    /**
     * Resolves the run's workload namespace, degrading to {@code null} (namespace-less launch) when
     * no tenant is resolvable or it resolves empty — a run with no tenant row must still prepare.
     */
    private String resolveNamespaceOrNull(UUID runId) {
        try {
            String ns = namespaceResolver.resolve(runId);
            return (ns == null || ns.isEmpty()) ? null : ns;
        } catch (RuntimeException e) {
            log.debug("No workload namespace resolved for run {}; launching namespace-less: {}", runId, e.toString());
            return null;
        }
    }

    /**
     * Whether the run's memory requests may exceed the deployment's defaults, degrading to {@code false}
     * when the policy throws: an unresolvable run must never lift the ceiling.
     */
    private boolean memoryCeilingExempt(UUID runId) {
        try {
            return memoryCeilingPolicy.exempt(runId);
        } catch (RuntimeException e) {
            log.debug("No memory-ceiling exemption resolved for run {}; capping: {}", runId, e.toString());
            return false;
        }
    }

    /**
     * Records what a Worker launched and transitions the execution to running.
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

    // Delegates to GraphSnapshotBuilder's rule so launch preparation and a run-start pre-flight
    // never disagree on which nodes run Claude.
    private static boolean runsClaude(ExecutionParams params) {
        Object raw = params.configJson() != null ? params.configJson().getOrDefault("executor_type", "ai") : "ai";
        return GraphSnapshotBuilder.runsClaude(String.valueOf(raw));
    }

    private ExecutionParams resolveExecutionParams(
            UUID nodeExecId, WorkflowRun run, UUID templateNodeId, PrepareWorkloadRequest req) {
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

        String dindImage = textOrNull(snapshot, "dind_image");
        String agentMemoryRequest = textOrNull(snapshot, "agent_memory_request");
        String dindMemoryRequest = textOrNull(snapshot, "dind_memory_request");

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
                nodeExecId,
                runId,
                templateNodeId,
                image,
                req.configJson(),
                enableDocker,
                dindImage,
                agentMemoryRequest,
                dindMemoryRequest,
                nodeCredentials,
                identity);
    }

    private static String textOrNull(JsonNode snapshot, String field) {
        JsonNode node = snapshot.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
