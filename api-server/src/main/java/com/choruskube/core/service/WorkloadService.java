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
 * <p>This service wraps the {@link WorkloadExecutor} with database operations,
 * providing atomic create-workload transactions that eliminate the race condition
 * in the previous design (where the orchestrator created a workload then made a
 * separate HTTP call to update the DB with the JobSecretHash).
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
     *
     * <p>Infrastructure details are resolved from the stored graph snapshot using
     * {@link #resolveExecutionParams(UUID, UUID, CreateWorkloadRequest)}.
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

    /** Cleans up all resources associated with a completed execution. */
    public void cleanupWorkload(UUID executionId) {
        executor.cleanup(executionId);
        log.info("Cleaned up workload for execution {}", executionId);
    }

    /** Returns recent log output from the agent container. */
    public WorkloadLogsResponse getWorkloadLogs(UUID executionId, int tailLines) {
        if (tailLines <= 0) {
            tailLines = 50;
        }
        String logs = executor.getLogs(executionId, tailLines);
        return new WorkloadLogsResponse(logs);
    }

    /** Stops a running execution. */
    public void terminateWorkload(UUID executionId) {
        executor.terminate(executionId);
        log.info("Terminated workload for execution {}", executionId);
    }

    /** Returns info about all running/completed executions. */
    public List<ExecutionInfo> listWorkloads() {
        return executor.listExecutions();
    }

    /** Checks executor backend connectivity. */
    public void healthCheck() {
        executor.healthCheck();
    }

    /**
     * Resolves all infrastructure parameters from the graph snapshot.
     *
     * <p>Resolution priority for image: node override → run input agent_image → system default.
     * Docker config comes from the snapshot (typically derived from GitRepo). Identity uses
     * system defaults.
     */
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

        // Find the node in the snapshot
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

        // Resolve secrets → CredentialSpec list
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

        // Resolve identity from executor defaults
        IdentitySpec identity = new IdentitySpec(defaultServiceAccount, 1000, false);

        return new ExecutionParams(
                nodeExecId, runId, templateNodeId, image, req.configJson(), enableDocker, nodeCredentials, identity);
    }
}
