package com.choruskube.core.service;

import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.DriftRecheckConfig;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle of an approval held while its run re-checks against a moved default branch: held, then
 * consumed (re-applied) or voided (left to a human, with the reason shown).
 */
@Service
public class GateRecheckService {

    public static final String REVIEWER_DECIDED = "A reviewer decided at the gate.";
    static final String ESCALATED = "The run was escalated to the Supervisor during the re-check, so the approval "
            + "given before it no longer applies.";
    static final String REVISED =
            "The spec was revised during the re-check, so it is no longer the spec that was approved.";
    static final String RUN_ENDED = "The run ended before the re-check finished.";

    private static final List<WorkflowRunStatus> ENDED =
            List.of(WorkflowRunStatus.completed, WorkflowRunStatus.failed, WorkflowRunStatus.cancelled);

    private final GateStampRepository stampRepo;
    private final NodeExecutionRepository execRepo;
    private final DecisionOptionsResolver decisionOptionsResolver;
    private final ObjectMapper objectMapper;

    public GateRecheckService(
            GateStampRepository stampRepo,
            NodeExecutionRepository execRepo,
            DecisionOptionsResolver decisionOptionsResolver,
            ObjectMapper objectMapper) {
        this.stampRepo = stampRepo;
        this.execRepo = execRepo;
        this.decisionOptionsResolver = decisionOptionsResolver;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GateStamp hold(
            UUID runId,
            NodeExecution gate,
            String gateLabel,
            StampMode mode,
            String feedback,
            String attachmentRefs,
            RoadmapCandidatesDocument approvedCandidates) {
        GateStamp stamp = new GateStamp();
        stamp.setWorkflowRunId(runId);
        stamp.setGateTemplateNodeId(gate.getTemplateNodeId());
        stamp.setGateLabel(gateLabel);
        stamp.setStampedNodeExecutionId(gate.getId());
        stamp.setStampMode(mode);
        stamp.setFeedback(feedback);
        stamp.setAttachmentRefs(attachmentRefs);
        stamp.setApprovedCandidates(approvedCandidates == null ? null : write(approvedCandidates));
        return stampRepo.save(stamp);
    }

    @Transactional
    public GateStamp holdAgain(UUID stampId) {
        GateStamp stamp = stampRepo
                .findById(stampId)
                .orElseThrow(() -> new NotFoundException("Gate stamp not found: " + stampId));
        stamp.setRecheckRounds(stamp.getRecheckRounds() + 1);
        return stampRepo.save(stamp);
    }

    @Transactional
    public void consume(UUID stampId) {
        stampRepo.findByIdAndStatus(stampId, GateStampStatus.pending).ifPresent(s -> {
            s.setStatus(GateStampStatus.consumed);
            stampRepo.save(s);
        });
    }

    /** {@code parkedAt}: the gate execution left awaiting a human, so the UI shows why only there. */
    @Transactional
    public void voidStamp(UUID stampId, String reason, UUID parkedAt) {
        stampRepo.findByIdAndStatus(stampId, GateStampStatus.pending).ifPresent(s -> {
            s.setParkedNodeExecutionId(parkedAt);
            markVoided(s, reason);
        });
    }

    @Transactional
    public void voidPendingFor(UUID runId, UUID gateTemplateNodeId, String reason) {
        stampRepo
                .findByWorkflowRunIdAndGateTemplateNodeIdAndStatus(runId, gateTemplateNodeId, GateStampStatus.pending)
                .forEach(s -> markVoided(s, reason));
    }

    @Transactional
    public int voidPendingOnEndedRuns() {
        List<GateStamp> orphaned = stampRepo.findPendingOnRunsIn(GateStampStatus.pending, ENDED);
        orphaned.forEach(s -> markVoided(s, RUN_ENDED));
        return orphaned.size();
    }

    public Optional<GateStamp> findPending(UUID stampId) {
        return stampRepo.findByIdAndStatus(stampId, GateStampStatus.pending);
    }

    public List<GateStamp> findReadyToReapply() {
        return stampRepo.findPendingWithGateIn(GateStampStatus.pending, NodeExecutionStatus.awaiting_human);
    }

    public List<GateStamp> listForRun(UUID runId) {
        return stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId);
    }

    public RoadmapCandidatesDocument approvedCandidates(GateStamp stamp) {
        if (stamp.getApprovedCandidates() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(stamp.getApprovedCandidates(), RoadmapCandidatesDocument.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Held approval " + stamp.getId() + " has an unreadable roadmap document", e);
        }
    }

    /**
     * Why the held approval must not re-apply although its gate is awaiting again: the run was
     * escalated since (a human is now involved), or, for {@code if_unchanged}, the routed-to node
     * revised what the reviewer approved.
     */
    public Optional<String> reasonNotToReapply(GateStamp stamp, JsonNode snapshot) {
        List<NodeExecution> since =
                execRepo.findByWorkflowRunIdAndStatus(stamp.getWorkflowRunId(), NodeExecutionStatus.completed).stream()
                        .filter(e ->
                                e.getCompletedAt() != null && e.getCompletedAt().isAfter(stamp.getCreatedAt()))
                        .toList();
        JsonNode hub = decisionOptionsResolver.findRoutingHub(snapshot);
        if (hub != null) {
            UUID hubId = UUID.fromString(hub.path("template_node_id").asText());
            if (since.stream().anyMatch(e -> hubId.equals(e.getTemplateNodeId()))) {
                return Optional.of(ESCALATED);
            }
        }
        if (stamp.getStampMode() == StampMode.if_unchanged) {
            UUID target = routeTarget(snapshot, stamp.getGateTemplateNodeId());
            if (target != null
                    && since.stream()
                            .anyMatch(e -> target.equals(e.getTemplateNodeId())
                                    && "revised".equalsIgnoreCase(e.getDecision()))) {
                return Optional.of(REVISED);
            }
        }
        return Optional.empty();
    }

    private UUID routeTarget(JsonNode snapshot, UUID gateId) {
        JsonNode config = decisionOptionsResolver.findNodeConfigOverrides(snapshot.get("nodes"), gateId);
        Optional<DriftRecheckConfig> recheck = DriftRecheckConfig.fromConfigOverrides(config);
        if (recheck.isEmpty()) {
            return null;
        }
        for (JsonNode edge : snapshot.path("edges")) {
            if (gateId.toString().equals(edge.path("source_node_id").asText())
                    && recheck.get()
                            .route()
                            .equalsIgnoreCase(edge.path("condition").asText())) {
                return UUID.fromString(edge.path("target_node_id").asText());
            }
        }
        return null;
    }

    private void markVoided(GateStamp stamp, String reason) {
        stamp.setStatus(GateStampStatus.voided);
        stamp.setVoidReason(reason);
        stampRepo.save(stamp);
    }

    private String write(RoadmapCandidatesDocument doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise the approved roadmap document", e);
        }
    }
}
