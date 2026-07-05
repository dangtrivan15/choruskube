package com.choruskube.core.service;

import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ArtifactResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactResolutionService.class);

    private final TemplateNodeRepository templateNodeRepo;
    private final NodeExecutionRepository nodeExecutionRepo;
    private final ObjectMapper objectMapper;

    public ArtifactResolutionService(
            TemplateNodeRepository templateNodeRepo,
            NodeExecutionRepository nodeExecutionRepo,
            ObjectMapper objectMapper) {
        this.templateNodeRepo = templateNodeRepo;
        this.nodeExecutionRepo = nodeExecutionRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves required input artifacts for a human-gate template node in a run.
     *
     * @return List of resolved artifact groups, or null if no declarations exist (legacy mode).
     */
    public List<ResolvedArtifactGroup> resolveRequiredArtifacts(UUID templateNodeId, UUID runId) {
        TemplateNode gateNode = templateNodeRepo.findById(templateNodeId).orElse(null);
        if (gateNode == null || gateNode.getRequiredInputArtifacts() == null) {
            return null;
        }

        try {
            JsonNode declarations = objectMapper.readTree(gateNode.getRequiredInputArtifacts());
            if (!declarations.isArray()) {
                return null;
            }

            UUID graphTemplateId = gateNode.getGraphTemplateId();
            if (graphTemplateId == null) {
                log.warn("TemplateNode {} has null graphTemplateId; skipping artifact resolution", templateNodeId);
                return null;
            }
            // Load all template nodes in the same template for label lookup
            List<TemplateNode> allTemplateNodes = templateNodeRepo.findByGraphTemplateId(graphTemplateId);
            Map<String, UUID> labelToTemplateNodeId = new HashMap<>();
            for (TemplateNode tn : allTemplateNodes) {
                labelToTemplateNodeId.put(tn.getLabel(), tn.getId());
            }

            // Load all completed executions in the run
            List<NodeExecution> completedExecs =
                    nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed);

            List<ResolvedArtifactGroup> groups = new ArrayList<>();
            for (JsonNode group : declarations) {
                String nodeLabel = group.path("template_node_label").asText(null);
                JsonNode artifactsNode = group.path("artifacts");
                if (nodeLabel == null || !artifactsNode.isArray()) {
                    log.warn(
                            "Skipping malformed required_input_artifacts group for template node {}: "
                                    + "missing template_node_label or non-array artifacts",
                            templateNodeId);
                    continue;
                }

                List<ResolvedArtifactEntry> entries = new ArrayList<>();
                for (JsonNode artifactNode : artifactsNode) {
                    String entryName = artifactNode.path("name").asText(null);
                    if (entryName == null || entryName.isBlank()) {
                        log.warn("Skipping artifact entry with missing name in template node {}", templateNodeId);
                        continue;
                    }
                    entries.add(new ResolvedArtifactEntry(
                            entryName, artifactNode.path("description").asText(null)));
                }

                UUID sourceTemplateNodeId = labelToTemplateNodeId.get(nodeLabel);
                UUID resolvedExecId = null;
                if (sourceTemplateNodeId != null) {
                    // Find the latest-iteration completed execution for this template node in the run
                    resolvedExecId = completedExecs.stream()
                            .filter(e -> e.getTemplateNodeId().equals(sourceTemplateNodeId))
                            .max(Comparator.comparingInt(NodeExecution::getIteration))
                            .map(NodeExecution::getId)
                            .orElse(null);
                }

                groups.add(new ResolvedArtifactGroup(resolvedExecId, nodeLabel, entries));
            }

            return groups;
        } catch (Exception e) {
            log.warn("Failed to resolve required artifacts for template node {}: {}", templateNodeId, e.getMessage());
            return null;
        }
    }
}
