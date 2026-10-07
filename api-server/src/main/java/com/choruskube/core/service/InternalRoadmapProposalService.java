package com.choruskube.core.service;

import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.RoadmapProposalValidationResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Guards the agent-facing roadmap routes: every one of them requires the calling node execution to
 * belong to the run named in the path, and the six direct write routes are refused outright when
 * that run's workflow reviews roadmap changes at a gate — {@code propose-roadmap} is the only path
 * left for such a run. Also answers {@code propose-roadmap}'s own validate call, at agent
 * strictness.
 */
@Service
public class InternalRoadmapProposalService {

    private final NodeExecutionRepository execRepo;
    private final RoadmapGateResolver gateResolver;
    private final RoadmapProposalValidator validator;
    private final ObjectMapper objectMapper;

    public InternalRoadmapProposalService(
            NodeExecutionRepository execRepo,
            RoadmapGateResolver gateResolver,
            RoadmapProposalValidator validator,
            ObjectMapper objectMapper) {
        this.execRepo = execRepo;
        this.gateResolver = gateResolver;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /**
     * Every agent-facing roadmap route (reads included) requires the calling node execution to
     * belong to the run named in the path — the node executions carry no authorization of their
     * own, inheriting it entirely from their parent run (see {@link NodeExecutionUtil}).
     */
    public void requireCallerInRun(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        NodeExecutionUtil.requireInRun(exec, runId);
    }

    /**
     * Refuses a direct roadmap write when the run's workflow reviews roadmap changes at a gate —
     * such a run may only describe deferred work in a proposal and install it with {@code
     * propose-roadmap}, so the reviewer creates the items on approval instead of an agent creating
     * them unreviewed.
     */
    public void assertAgentRoadmapWriteAllowed(UUID runId, UUID nodeExecId) {
        requireCallerInRun(runId, nodeExecId);
        Optional<RoadmapGateResolver.RoadmapGate> gate = gateResolver.forRun(runId);
        if (gate.isPresent()) {
            throw new ForbiddenException("This workflow reviews roadmap changes at its '"
                    + gate.get().label()
                    + "' gate, so agents cannot create or edit roadmap items directly. Describe them in a"
                    + " proposal and install it with `propose-roadmap --file <path>`; the reviewer creates them"
                    + " on approval.");
        }
    }

    public RoadmapProposalValidationResponse validate(UUID runId, UUID nodeExecId, JsonNode body) {
        requireCallerInRun(runId, nodeExecId);
        RoadmapGateResolver.RoadmapGate gate = gateResolver
                .forRun(runId)
                .orElseThrow(() -> new ConflictException(
                        "This workflow has no roadmap review gate, so a proposal would never be reviewed. File a"
                                + " GitHub issue for deferred work instead."));
        RoadmapCandidatesDocument doc = RoadmapDocumentBinding.bind(objectMapper, body);

        List<String> violations =
                validator.validate(runId, doc, gate.mode(), RoadmapProposalValidator.Strictness.AGENT);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }

        RoadmapProposalValidator.Summary summary = validator.summarize(doc);
        return new RoadmapProposalValidationResponse(
                gate.mode().name(),
                gate.label(),
                summary.newEpics(),
                summary.newStories(),
                summary.newTasks(),
                summary.existingItems(),
                summary.dependencies());
    }
}
