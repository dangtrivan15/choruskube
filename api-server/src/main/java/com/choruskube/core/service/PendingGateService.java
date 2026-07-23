package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.PendingGateCountResponse;
import com.choruskube.core.dto.PendingGateResponse;
import com.choruskube.core.dto.PredecessorOutput;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.scope.ScopeProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
public class PendingGateService {

    private static final Logger logger = LoggerFactory.getLogger(PendingGateService.class);

    private final NodeExecutionRepository execRepo;
    private final WorkflowRunRepository runRepo;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final ObjectMapper objectMapper;
    private final AuthorizationService authService;
    private final ArtifactResolutionService artifactResolutionService;
    private final ScopeProvider scopeProvider;
    private final DecisionOptionsResolver decisionOptionsResolver;
    private final RoadmapCandidatesArtifactResolver candidatesArtifactResolver;

    public PendingGateService(
            NodeExecutionRepository execRepo,
            WorkflowRunRepository runRepo,
            GraphSnapshotBuilder snapshotBuilder,
            ObjectMapper objectMapper,
            AuthorizationService authService,
            ArtifactResolutionService artifactResolutionService,
            ScopeProvider scopeProvider,
            DecisionOptionsResolver decisionOptionsResolver,
            RoadmapCandidatesArtifactResolver candidatesArtifactResolver) {
        this.execRepo = execRepo;
        this.runRepo = runRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.artifactResolutionService = artifactResolutionService;
        this.scopeProvider = scopeProvider;
        this.decisionOptionsResolver = decisionOptionsResolver;
        this.candidatesArtifactResolver = candidatesArtifactResolver;
    }

    private static final List<NodeExecutionStatus> GATE_STATUSES =
            List.of(NodeExecutionStatus.awaiting_human, NodeExecutionStatus.live_chat);

    public List<PendingGateResponse> getPendingGates() {
        Specification<NodeExecution> spec = gateStatusSpec().and(scopeProvider.scope(NodeExecution.class));
        List<NodeExecution> gateExecs = execRepo.findAll(spec);
        if (gateExecs.isEmpty()) {
            return List.of();
        }

        Set<UUID> runIds =
                gateExecs.stream().map(NodeExecution::getWorkflowRunId).collect(Collectors.toSet());
        Map<UUID, WorkflowRun> runsById =
                runRepo.findAllById(runIds).stream().collect(Collectors.toMap(WorkflowRun::getId, r -> r));

        // Single batch query for all executions across all related runs,
        // then group by runId — avoids issuing one query per run.
        Map<UUID, List<NodeExecution>> execsByRun = execRepo.findByWorkflowRunIdIn(runIds).stream()
                .collect(Collectors.groupingBy(NodeExecution::getWorkflowRunId));

        return gateExecs.stream()
                .map(exec -> buildPendingGateResponse(exec, runsById, execsByRun))
                .filter(Objects::nonNull)
                .toList();
    }

    public Page<PendingGateResponse> getPendingGates(Pageable pageable) {
        Specification<NodeExecution> spec = gateStatusSpec().and(scopeProvider.scope(NodeExecution.class));
        Page<NodeExecution> gatePage = execRepo.findAll(spec, pageable);
        if (gatePage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        Set<UUID> runIds =
                gatePage.stream().map(NodeExecution::getWorkflowRunId).collect(Collectors.toSet());
        Map<UUID, WorkflowRun> runsById =
                runRepo.findAllById(runIds).stream().collect(Collectors.toMap(WorkflowRun::getId, r -> r));

        // Single batch query for all executions across all related runs,
        // then group by runId — avoids issuing one query per run.
        Map<UUID, List<NodeExecution>> execsByRun = execRepo.findByWorkflowRunIdIn(runIds).stream()
                .collect(Collectors.groupingBy(NodeExecution::getWorkflowRunId));

        List<PendingGateResponse> responses = gatePage.stream()
                .map(exec -> buildPendingGateResponse(exec, runsById, execsByRun))
                .filter(Objects::nonNull)
                .toList();

        return new PageImpl<>(responses, pageable, gatePage.getTotalElements());
    }

    public PendingGateCountResponse getPendingGateCount() {
        Specification<NodeExecution> spec = gateStatusSpec().and(scopeProvider.scope(NodeExecution.class));
        long count = execRepo.count(spec);
        return new PendingGateCountResponse((int) count);
    }

    /** Filters node executions to the gate statuses (awaiting_human, live_chat). */
    private static Specification<NodeExecution> gateStatusSpec() {
        return (root, query, cb) -> root.get("status").in(GATE_STATUSES);
    }

    private PendingGateResponse buildPendingGateResponse(
            NodeExecution exec, Map<UUID, WorkflowRun> runsById, Map<UUID, List<NodeExecution>> execsByRun) {
        WorkflowRun run = runsById.get(exec.getWorkflowRunId());
        if (run == null) {
            logger.warn("Run not found for node execution {}", exec.getId());
            return null;
        }

        String runName = run.getName() != null ? run.getName() : "";

        // Build graph snapshot on-demand to get node label, timeout, and predecessor info
        String nodeLabel = "Unknown";
        Integer timeoutSeconds = null;
        List<PredecessorOutput> predecessorOutputs = List.of();
        List<String> decisionOptions = List.of();

        {
            try {
                JsonNode snapshot = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
                JsonNode nodesArr = snapshot.get("nodes");
                JsonNode edgesArr = snapshot.get("edges");

                // Build nodeId → snapshot node map
                Map<UUID, JsonNode> nodeMap = new HashMap<>();
                if (nodesArr != null) {
                    for (JsonNode n : nodesArr) {
                        UUID nodeId = UUID.fromString(n.get("template_node_id").asText());
                        nodeMap.put(nodeId, n);
                    }
                }

                // Get label and timeout for this node
                JsonNode thisNode = nodeMap.get(exec.getTemplateNodeId());
                if (thisNode != null) {
                    nodeLabel = thisNode.has("label") ? thisNode.get("label").asText() : "Unknown";
                    if (thisNode.has("timeout_seconds")
                            && !thisNode.get("timeout_seconds").isNull()) {
                        timeoutSeconds = thisNode.get("timeout_seconds").asInt();
                    }
                }

                // Find predecessor node IDs via edges
                predecessorOutputs = findPredecessorOutputs(
                        exec.getTemplateNodeId(), edgesArr, nodeMap, execsByRun.getOrDefault(run.getId(), List.of()));

                // Decision options come from the union of outgoing edge conditions and this
                // node's terminal_decisions config — same source RunService's validator uses,
                // so the UI cannot drift from the contract.
                JsonNode thisNodeConfigOverrides = thisNode != null ? thisNode.get("config_overrides") : null;
                decisionOptions =
                        decisionOptionsResolver.resolve(edgesArr, exec.getTemplateNodeId(), thisNodeConfigOverrides);
            } catch (Exception e) {
                logger.warn("Failed to parse graph snapshot for run {}: {}", run.getId(), e.getMessage());
            }
        }

        List<ResolvedArtifactGroup> requiredArtifacts =
                artifactResolutionService.resolveRequiredArtifacts(exec.getTemplateNodeId(), exec.getWorkflowRunId());

        List<CandidateEpicProposal> candidateBreakdown =
                candidatesArtifactResolver.resolve(run.getId(), requiredArtifacts);

        return new PendingGateResponse(
                exec.getId(),
                run.getId(),
                run.getStatus().name(),
                runName,
                nodeLabel,
                exec.getIteration(),
                timeoutSeconds,
                exec.getStartedAt(),
                exec.getStatus().name(),
                predecessorOutputs,
                requiredArtifacts,
                decisionOptions,
                candidateBreakdown);
    }

    private List<PredecessorOutput> findPredecessorOutputs(
            UUID targetNodeId, JsonNode edgesArr, Map<UUID, JsonNode> nodeMap, List<NodeExecution> allExecs) {
        if (edgesArr == null) {
            return List.of();
        }

        // Find direct predecessors (one level)
        Set<UUID> predecessorNodeIds = new LinkedHashSet<>();
        for (JsonNode edge : edgesArr) {
            UUID tgtId = UUID.fromString(edge.get("target_node_id").asText());
            if (tgtId.equals(targetNodeId)) {
                UUID srcId = UUID.fromString(edge.get("source_node_id").asText());
                predecessorNodeIds.add(srcId);
            }
        }

        return predecessorNodeIds.stream()
                .map(predNodeId -> {
                    // Get label from snapshot
                    JsonNode predNode = nodeMap.get(predNodeId);
                    final String predLabel = (predNode != null && predNode.has("label"))
                            ? predNode.get("label").asText()
                            : "";

                    // Find the latest completed execution for this predecessor
                    return allExecs.stream()
                            .filter(e -> e.getTemplateNodeId().equals(predNodeId)
                                    && e.getStatus() == NodeExecutionStatus.completed)
                            .max(Comparator.comparingInt(NodeExecution::getIteration))
                            .map(e -> new PredecessorOutput(
                                    e.getTemplateNodeId(), predLabel, e.getResult(), e.getArtifactRefs(), e.getId()))
                            .orElse(null);
                })
                .filter(Objects::nonNull)
                .toList();
    }
}
