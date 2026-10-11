package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GateRecheckServiceTest {

    @Mock
    private GateStampRepository stampRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GateRecheckService service;

    private final UUID runId = UUID.randomUUID();
    private final UUID specReview = UUID.randomUUID();
    private final UUID approve = UUID.randomUUID();
    private final UUID supervisor = UUID.randomUUID();
    private final Instant held = Instant.parse("2026-10-11T10:00:00Z");
    private GateStamp stamp;
    private JsonNode snapshot;

    @BeforeEach
    void setUp() throws Exception {
        service = new GateRecheckService(stampRepo, execRepo, new DecisionOptionsResolver(), objectMapper);
        stamp = new GateStamp();
        stamp.setId(UUID.randomUUID());
        stamp.setWorkflowRunId(runId);
        stamp.setGateTemplateNodeId(approve);
        stamp.setStampMode(StampMode.if_unchanged);
        stamp.setCreatedAt(held);
        snapshot = objectMapper.readTree("""
                {"nodes":[
                  {"template_node_id":"%s","label":"spec_review","executor_type":"ai"},
                  {"template_node_id":"%s","label":"approve","executor_type":"human",
                   "config_overrides":{"drift_recheck":{"route":"rereview","stamp":"if_unchanged"}}},
                  {"template_node_id":"%s","label":"supervisor","executor_type":"human",
                   "config_overrides":{"routing_hub":true}}],
                 "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"rereview"}]}""".formatted(specReview, approve, supervisor, approve, specReview));
    }

    @Test
    void supervisorCompletedAfterStamp_isEscalated() {
        completed(execution(supervisor, held.plusSeconds(30), null));
        assertThat(service.reasonNotToReapply(stamp, snapshot)).contains(GateRecheckService.ESCALATED);
    }

    @Test
    void supervisorCompletedBeforeStamp_isIgnored() {
        completed(execution(supervisor, held.minusSeconds(30), null));
        assertThat(service.reasonNotToReapply(stamp, snapshot)).isEmpty();
    }

    @Test
    void ifUnchanged_revisedSinceStamp_isRevised() {
        completed(
                execution(specReview, held.plusSeconds(10), "revised"),
                execution(specReview, held.plusSeconds(20), "approved"));
        assertThat(service.reasonNotToReapply(stamp, snapshot)).contains(GateRecheckService.REVISED);
    }

    @Test
    void ifUnchanged_approvedSinceStamp_reapplies() {
        completed(execution(specReview, held.plusSeconds(20), "approved"));
        assertThat(service.reasonNotToReapply(stamp, snapshot)).isEmpty();
    }

    @Test
    void always_revisedSinceStamp_reapplies() {
        stamp.setStampMode(StampMode.always);
        completed(execution(specReview, held.plusSeconds(10), "revised"));
        assertThat(service.reasonNotToReapply(stamp, snapshot)).isEmpty();
    }

    @Test
    void approvedCandidates_roundTrips() throws Exception {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(List.of(), List.of(), List.of());
        stamp.setApprovedCandidates(objectMapper.writeValueAsString(doc));
        assertThat(service.approvedCandidates(stamp)).isEqualTo(doc);

        stamp.setApprovedCandidates(null);
        assertThat(service.approvedCandidates(stamp)).isNull();
    }

    private void completed(NodeExecution... executions) {
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(executions));
    }

    private NodeExecution execution(UUID templateNodeId, Instant completedAt, String decision) {
        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setCompletedAt(completedAt);
        exec.setDecision(decision);
        return exec;
    }
}
