package com.choruskube.core.service;

import com.choruskube.core.dto.CreateWorkloadRequest;
import com.choruskube.core.dto.CreateWorkloadResponse;
import com.choruskube.core.dto.WorkloadLogsResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.executor.*;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer for workload execution operations.
 *
 * <p>Creating the workload and writing its {@code job_secret_hash} are one transaction on
 * purpose: split across a create call and a separate DB update, the pod is live before its hash
 * is persisted, so its own callbacks 401 at {@code InternalAuthFilter} and the Job orphans.
 *
 * <p>Infrastructure details (image, secrets, docker config, identity) are resolved
 * from the stored graph snapshot rather than being passed by the caller.
 */
@Service
public class WorkloadService {

    private static final Logger log = LoggerFactory.getLogger(WorkloadService.class);

    private final WorkloadExecutor executor;
    private final NodeExecutionRepository execRepo;
    private final RunEventPublisher eventPublisher;
    private final WorkflowRunRepository runRepo;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final ObjectMapper objectMapper;
    private final String defaultAgentImage;
    private final String defaultServiceAccount;

    public WorkloadService(
            WorkloadExecutor executor,
            NodeExecutionRepository execRepo,
            RunEventPublisher eventPublisher,
            WorkflowRunRepository runRepo,
            GraphSnapshotBuilder snapshotBuilder,
            ObjectMapper objectMapper,
            @Qualifier("executorDefaultAgentImage") String defaultAgentImage,
            @Value("${executor.k8s.agent-service-account:choruskube-agent}") String defaultServiceAccount) {
        this.executor = executor;
        this.execRepo = execRepo;
        this.eventPublisher = eventPublisher;
        this.runRepo = runRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.objectMapper = objectMapper;
        this.defaultAgentImage = defaultAgentImage;
        this.defaultServiceAccount = defaultServiceAccount;
    }

    /**
     * Creates a workload (launches an agent container) and atomically updates the
     * node execution with pod_name, job_secret_hash, and status=running.
     */
    @Transactional
    public CreateWorkloadResponse createWorkload(UUID runId, UUID nodeExecId, CreateWorkloadRequest req) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));

        ExecutionParams params = resolveExecutionParams(nodeExecId, run, exec.getTemplateNodeId(), req);

        ExecutionResult result = executor.execute(params);

        exec.setStatus(NodeExecutionStatus.running);
        exec.setPodName(result.executionHandle());
        exec.setJobSecretHash(result.jobSecretHash());
        if (exec.getStartedAt() == null) {
            exec.setStartedAt(Instant.now());
        }
        execRepo.save(exec);

        eventPublisher.publishNodeStatusChanged(runId, nodeExecId, "running");
        log.info("Created workload for execution {}: handle={}", nodeExecId, result.executionHandle());

        return new CreateWorkloadResponse(result.executionHandle(), result.jobSecretHash());
    }

    public void cleanupWorkload(UUID executionId) {
        executor.cleanup(executionId);
        log.info("Cleaned up workload for execution {}", executionId);
    }

    public WorkloadLogsResponse getWorkloadLogs(UUID executionId, int tailLines) {
        if (tailLines <= 0) {
            tailLines = 50;
        }
        String logs = executor.getLogs(executionId, tailLines);
        return new WorkloadLogsResponse(logs);
    }

    public void terminateWorkload(UUID executionId) {
        executor.terminate(executionId);
        log.info("Terminated workload for execution {}", executionId);
    }

    public List<ExecutionInfo> listWorkloads() {
        return executor.listExecutions();
    }

    public void healthCheck() {
        executor.healthCheck();
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

        // Resolve image: node override → run input agent_image → system default
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
