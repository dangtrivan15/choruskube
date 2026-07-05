package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.PredecessorArtifactsResponse;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransitivePredecessorTest {

    private NodeExecutionRepository execRepo;
    private WorkflowRunRepository runRepo;
    private GraphSnapshotBuilder snapshotBuilder;
    private InternalRunService service;

    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        execRepo = mock(NodeExecutionRepository.class);
        runRepo = mock(WorkflowRunRepository.class);
        snapshotBuilder = mock(GraphSnapshotBuilder.class);
        service = new InternalRunService(
                runRepo,
                execRepo,
                null,
                snapshotBuilder,
                null,
                new ObjectMapper(),
                null,
                null,
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    // --- helpers ---

    private NodeExecution makeExec(UUID id, UUID templateNodeId, NodeExecutionStatus status, int iteration) {
        NodeExecution e = new NodeExecution();
        e.setId(id);
        e.setWorkflowRunId(runId);
        e.setTemplateNodeId(templateNodeId);
        e.setStatus(status);
        e.setIteration(iteration);
        e.setArtifactRefs("{\"file\":\"out.txt\"}");
        e.setResult("ok");
        return e;
    }

    private String buildSnapshot(List<Map<String, String>> nodes, List<Map<String, String>> edges) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("nodes", nodes);
        snap.put("edges", edges);
        try {
            return new ObjectMapper().writeValueAsString(snap);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private Map<String, String> node(UUID templateNodeId, String label) {
        return Map.of("template_node_id", templateNodeId.toString(), "label", label);
    }

    private Map<String, String> edge(UUID source, UUID target) {
        return Map.of("source_node_id", source.toString(), "target_node_id", target.toString());
    }

    private void stubRun(String snapshot) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
    }

    // --- test cases ---

    @Test
    void linearChainReturnsTransitivePredecessors() {
        // A → B → C — querying C should return both A and B
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();

        String snapshot = buildSnapshot(
                List.of(node(nodeA, "Node A"), node(nodeB, "Node B"), node(nodeC, "Node C")),
                List.of(edge(nodeA, nodeB), edge(nodeB, nodeC)));
        stubRun(snapshot);

        UUID execIdC = UUID.randomUUID();
        NodeExecution execC = makeExec(execIdC, nodeC, NodeExecutionStatus.pending, 1);
        NodeExecution execB = makeExec(UUID.randomUUID(), nodeB, NodeExecutionStatus.completed, 1);
        NodeExecution execA = makeExec(UUID.randomUUID(), nodeA, NodeExecutionStatus.completed, 1);

        when(execRepo.findById(execIdC)).thenReturn(Optional.of(execC));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(execA, execB, execC));

        List<PredecessorArtifactsResponse> result = service.getCompletedPredecessors(runId, execIdC);

        assertThat(result).hasSize(2);
        Set<UUID> returnedIds = new HashSet<>();
        for (var r : result) {
            returnedIds.add(r.templateNodeId());
        }
        assertThat(returnedIds).containsExactlyInAnyOrder(nodeA, nodeB);
    }

    @Test
    void directPredecessorOnly() {
        // A → B — querying B should return only A
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        String snapshot =
                buildSnapshot(List.of(node(nodeA, "Node A"), node(nodeB, "Node B")), List.of(edge(nodeA, nodeB)));
        stubRun(snapshot);

        UUID execIdB = UUID.randomUUID();
        NodeExecution execB = makeExec(execIdB, nodeB, NodeExecutionStatus.pending, 1);
        NodeExecution execA = makeExec(UUID.randomUUID(), nodeA, NodeExecutionStatus.completed, 1);

        when(execRepo.findById(execIdB)).thenReturn(Optional.of(execB));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(execA, execB));

        List<PredecessorArtifactsResponse> result = service.getCompletedPredecessors(runId, execIdB);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).templateNodeId()).isEqualTo(nodeA);
    }

    @Test
    void fanInReturnsBothDirectPredecessors() {
        // A → C, B → C — querying C should return A and B
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();

        String snapshot = buildSnapshot(
                List.of(node(nodeA, "Node A"), node(nodeB, "Node B"), node(nodeC, "Node C")),
                List.of(edge(nodeA, nodeC), edge(nodeB, nodeC)));
        stubRun(snapshot);

        UUID execIdC = UUID.randomUUID();
        NodeExecution execC = makeExec(execIdC, nodeC, NodeExecutionStatus.pending, 1);
        NodeExecution execA = makeExec(UUID.randomUUID(), nodeA, NodeExecutionStatus.completed, 1);
        NodeExecution execB = makeExec(UUID.randomUUID(), nodeB, NodeExecutionStatus.completed, 1);

        when(execRepo.findById(execIdC)).thenReturn(Optional.of(execC));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(execA, execB, execC));

        List<PredecessorArtifactsResponse> result = service.getCompletedPredecessors(runId, execIdC);

        assertThat(result).hasSize(2);
        Set<UUID> returnedIds = new HashSet<>();
        for (var r : result) {
            returnedIds.add(r.templateNodeId());
        }
        assertThat(returnedIds).containsExactlyInAnyOrder(nodeA, nodeB);
    }

    @Test
    void labelsAreIncludedInResponse() {
        // A → B — verify label is populated from snapshot nodes
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        String snapshot =
                buildSnapshot(List.of(node(nodeA, "Writer"), node(nodeB, "Reviewer")), List.of(edge(nodeA, nodeB)));
        stubRun(snapshot);

        UUID execIdB = UUID.randomUUID();
        NodeExecution execB = makeExec(execIdB, nodeB, NodeExecutionStatus.pending, 1);
        NodeExecution execA = makeExec(UUID.randomUUID(), nodeA, NodeExecutionStatus.completed, 1);

        when(execRepo.findById(execIdB)).thenReturn(Optional.of(execB));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(execA, execB));

        List<PredecessorArtifactsResponse> result = service.getCompletedPredecessors(runId, execIdB);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isEqualTo("Writer");
    }

    @Test
    void onlyCompletedExecsAreReturned() {
        // A → B → C — A is completed, B is running. Querying C should return only A.
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();

        String snapshot = buildSnapshot(
                List.of(node(nodeA, "Node A"), node(nodeB, "Node B"), node(nodeC, "Node C")),
                List.of(edge(nodeA, nodeB), edge(nodeB, nodeC)));
        stubRun(snapshot);

        UUID execIdC = UUID.randomUUID();
        NodeExecution execC = makeExec(execIdC, nodeC, NodeExecutionStatus.pending, 1);
        NodeExecution execB = makeExec(UUID.randomUUID(), nodeB, NodeExecutionStatus.running, 1);
        NodeExecution execA = makeExec(UUID.randomUUID(), nodeA, NodeExecutionStatus.completed, 1);

        when(execRepo.findById(execIdC)).thenReturn(Optional.of(execC));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(execA, execB, execC));

        List<PredecessorArtifactsResponse> result = service.getCompletedPredecessors(runId, execIdC);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).templateNodeId()).isEqualTo(nodeA);
    }

    @Test
    void highestIterationIsSelected() {
        // A → B — A has two completed executions (iter 1 and iter 2). Should pick iter 2.
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        String snapshot =
                buildSnapshot(List.of(node(nodeA, "Node A"), node(nodeB, "Node B")), List.of(edge(nodeA, nodeB)));
        stubRun(snapshot);

        UUID execIdB = UUID.randomUUID();
        NodeExecution execB = makeExec(execIdB, nodeB, NodeExecutionStatus.pending, 1);
        NodeExecution execA1 = makeExec(UUID.randomUUID(), nodeA, NodeExecutionStatus.completed, 1);
        execA1.setResult("iter1-result");
        NodeExecution execA2 = makeExec(UUID.randomUUID(), nodeA, NodeExecutionStatus.completed, 2);
        execA2.setResult("iter2-result");

        when(execRepo.findById(execIdB)).thenReturn(Optional.of(execB));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(execA1, execA2, execB));

        List<PredecessorArtifactsResponse> result = service.getCompletedPredecessors(runId, execIdB);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).result()).isEqualTo("iter2-result");
    }
}
