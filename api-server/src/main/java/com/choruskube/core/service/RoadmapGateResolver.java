package com.choruskube.core.service;

import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Discovers whether a run's workflow declares a roadmap gate — a node whose {@code
 * config_overrides} carries a known {@link RoadmapMaterializeMode} — and, if so, which node and
 * mode. Shared by the agent write-refusal check and the validate route so both answer from the
 * same source of truth as {@code GraphValidationService}'s "at most one roadmap gate" rule.
 */
@Service
public class RoadmapGateResolver {

    /** A roadmap gate: the template node that declares {@code materialize}, its label, and its mode. */
    public record RoadmapGate(UUID templateNodeId, String label, RoadmapMaterializeMode mode) {}

    private final WorkflowRunRepository runRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final ObjectMapper objectMapper;

    public RoadmapGateResolver(
            WorkflowRunRepository runRepo, TemplateNodeRepository templateNodeRepo, ObjectMapper objectMapper) {
        this.runRepo = runRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.objectMapper = objectMapper;
    }

    public Optional<RoadmapGate> forRun(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        for (TemplateNode node : templateNodeRepo.findByGraphTemplateId(run.getGraphTemplateId())) {
            Optional<RoadmapMaterializeMode> mode = parseMode(node);
            if (mode.isPresent()) {
                return Optional.of(new RoadmapGate(node.getId(), node.getLabel(), mode.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<RoadmapMaterializeMode> parseMode(TemplateNode node) {
        String overridesStr = node.getConfigOverrides();
        if (overridesStr == null || overridesStr.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode overrides = objectMapper.readTree(overridesStr);
            return RoadmapMaterializeMode.fromConfigOverrides(overrides);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
