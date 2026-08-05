package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.dto.InputArtifactManifest;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.ReviewerType;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ArtifactResolutionServiceTest {

    private TemplateNodeRepository templateNodeRepo;
    private NodeExecutionRepository nodeExecutionRepo;
    private WorkflowRunRepository workflowRunRepo;
    private GraphSnapshotBuilder snapshotBuilder;
    private ArtifactResolutionService service;

    @BeforeEach
    void setUp() {
        templateNodeRepo = Mockito.mock(TemplateNodeRepository.class);
        nodeExecutionRepo = Mockito.mock(NodeExecutionRepository.class);
        workflowRunRepo = Mockito.mock(WorkflowRunRepository.class);
        snapshotBuilder = Mockito.mock(GraphSnapshotBuilder.class);
        service = new ArtifactResolutionService(
                templateNodeRepo, nodeExecutionRepo, workflowRunRepo, snapshotBuilder, new ObjectMapper());
    }

    @Test
    void resolveRequiredArtifacts_returnsNullWhenTemplateNodeNotFound() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.empty());

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        assertThat(result).isNull();
    }

    @Test
    void resolveRequiredArtifacts_returnsNullWhenRequiredInputArtifactsIsNull() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        TemplateNode gateNode = new TemplateNode();
        gateNode.setId(templateNodeId);
        gateNode.setGraphTemplateId(graphTemplateId);
        gateNode.setLabel("review_gate");
        // requiredInputArtifacts is null (not set)

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.of(gateNode));

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        assertThat(result).isNull();
    }

    @Test
    void resolveRequiredArtifacts_returnsGroupsWithResolvedExecutionId() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();
        UUID sourceTemplateNodeId = UUID.randomUUID();
        UUID completedExecId = UUID.randomUUID();

        TemplateNode gateNode = new TemplateNode();
        gateNode.setId(templateNodeId);
        gateNode.setGraphTemplateId(graphTemplateId);
        gateNode.setLabel("review_gate");
        gateNode.setRequiredInputArtifacts(
                "[{\"template_node_label\":\"draft\",\"artifacts\":[{\"name\":\"draft-output\",\"description\":\"Mock draft artifact\"}]}]");

        TemplateNode draftNode = new TemplateNode();
        draftNode.setId(sourceTemplateNodeId);
        draftNode.setGraphTemplateId(graphTemplateId);
        draftNode.setLabel("draft");

        NodeExecution completedExec = new NodeExecution();
        completedExec.setId(completedExecId);
        completedExec.setWorkflowRunId(runId);
        completedExec.setTemplateNodeId(sourceTemplateNodeId);
        completedExec.setStatus(NodeExecutionStatus.completed);
        completedExec.setIteration(1);

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.of(gateNode));
        Mockito.when(templateNodeRepo.findByGraphTemplateId(graphTemplateId)).thenReturn(List.of(gateNode, draftNode));
        Mockito.when(nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(completedExec));

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        assertThat(result).isNotNull().hasSize(1);
        ResolvedArtifactGroup group = result.get(0);
        assertThat(group.nodeLabel()).isEqualTo("draft");
        assertThat(group.nodeExecutionId()).isEqualTo(completedExecId);
        assertThat(group.artifacts()).hasSize(1);
        assertThat(group.artifacts().get(0).name()).isEqualTo("draft-output");
        assertThat(group.artifacts().get(0).description()).isEqualTo("Mock draft artifact");
    }

    @Test
    void resolveRequiredArtifacts_returnsNullExecutionIdWhenNoPredecessorExecution() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();
        UUID sourceTemplateNodeId = UUID.randomUUID();

        TemplateNode gateNode = new TemplateNode();
        gateNode.setId(templateNodeId);
        gateNode.setGraphTemplateId(graphTemplateId);
        gateNode.setLabel("review_gate");
        gateNode.setRequiredInputArtifacts(
                "[{\"template_node_label\":\"draft\",\"artifacts\":[{\"name\":\"draft-output\",\"description\":\"Mock draft artifact\"}]}]");

        TemplateNode draftNode = new TemplateNode();
        draftNode.setId(sourceTemplateNodeId);
        draftNode.setGraphTemplateId(graphTemplateId);
        draftNode.setLabel("draft");

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.of(gateNode));
        Mockito.when(templateNodeRepo.findByGraphTemplateId(graphTemplateId)).thenReturn(List.of(gateNode, draftNode));
        Mockito.when(nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of());

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        assertThat(result).isNotNull().hasSize(1);
        ResolvedArtifactGroup group = result.get(0);
        assertThat(group.nodeLabel()).isEqualTo("draft");
        assertThat(group.nodeExecutionId()).isNull();
        assertThat(group.artifacts()).hasSize(1);
    }

    @Test
    void resolveRequiredArtifacts_returnsNullWhenRequiredInputArtifactsIsNotArray() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        TemplateNode gateNode = new TemplateNode();
        gateNode.setId(templateNodeId);
        gateNode.setGraphTemplateId(graphTemplateId);
        gateNode.setRequiredInputArtifacts("{\"not\": \"an array\"}");

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.of(gateNode));

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        assertThat(result).isNull();
    }

    @Test
    void resolveRequiredArtifacts_skipsGroupsWithNullLabel() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();

        TemplateNode gateNode = new TemplateNode();
        gateNode.setId(templateNodeId);
        gateNode.setGraphTemplateId(graphTemplateId);
        gateNode.setLabel("review_gate");
        // One group has null label (no template_node_label key), one is valid
        gateNode.setRequiredInputArtifacts(
                "[{\"artifacts\":[{\"name\":\"missing.md\",\"description\":\"skipped\"}]},"
                        + "{\"template_node_label\":\"draft\",\"artifacts\":[{\"name\":\"draft-output\",\"description\":\"ok\"}]}]");

        UUID draftNodeId = UUID.randomUUID();
        TemplateNode draftNode = new TemplateNode();
        draftNode.setId(draftNodeId);
        draftNode.setGraphTemplateId(graphTemplateId);
        draftNode.setLabel("draft");

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.of(gateNode));
        Mockito.when(templateNodeRepo.findByGraphTemplateId(graphTemplateId)).thenReturn(List.of(gateNode, draftNode));
        Mockito.when(nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of());

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        // Only the valid group should be present
        assertThat(result).isNotNull().hasSize(1);
        assertThat(result.get(0).nodeLabel()).isEqualTo("draft");
    }

    @Test
    void resolveRequiredArtifacts_multipleGroups_allResolved() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();
        UUID srcNodeId1 = UUID.randomUUID();
        UUID srcNodeId2 = UUID.randomUUID();
        UUID execId1 = UUID.randomUUID();
        UUID execId2 = UUID.randomUUID();

        TemplateNode gateNode = new TemplateNode();
        gateNode.setId(templateNodeId);
        gateNode.setGraphTemplateId(graphTemplateId);
        gateNode.setLabel("review_gate");
        gateNode.setRequiredInputArtifacts(
                "[{\"template_node_label\":\"draft\",\"artifacts\":[{\"name\":\"draft.md\",\"description\":\"d\"}]},"
                        + "{\"template_node_label\":\"review\",\"artifacts\":[{\"name\":\"review.md\",\"description\":\"r\"}]}]");

        TemplateNode srcNode1 = new TemplateNode();
        srcNode1.setId(srcNodeId1);
        srcNode1.setGraphTemplateId(graphTemplateId);
        srcNode1.setLabel("draft");

        TemplateNode srcNode2 = new TemplateNode();
        srcNode2.setId(srcNodeId2);
        srcNode2.setGraphTemplateId(graphTemplateId);
        srcNode2.setLabel("review");

        NodeExecution exec1 = new NodeExecution();
        exec1.setId(execId1);
        exec1.setTemplateNodeId(srcNodeId1);
        exec1.setStatus(NodeExecutionStatus.completed);
        exec1.setIteration(1);

        NodeExecution exec2 = new NodeExecution();
        exec2.setId(execId2);
        exec2.setTemplateNodeId(srcNodeId2);
        exec2.setStatus(NodeExecutionStatus.completed);
        exec2.setIteration(1);

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.of(gateNode));
        Mockito.when(templateNodeRepo.findByGraphTemplateId(graphTemplateId))
                .thenReturn(List.of(gateNode, srcNode1, srcNode2));
        Mockito.when(nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(exec1, exec2));

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        assertThat(result).isNotNull().hasSize(2);
        assertThat(result.get(0).nodeExecutionId()).isEqualTo(execId1);
        assertThat(result.get(1).nodeExecutionId()).isEqualTo(execId2);
    }

    @Test
    void resolveRequiredArtifacts_highestIterationSelected() {
        UUID templateNodeId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID graphTemplateId = UUID.randomUUID();
        UUID srcNodeId = UUID.randomUUID();
        UUID exec1Id = UUID.randomUUID();
        UUID exec2Id = UUID.randomUUID();

        TemplateNode gateNode = new TemplateNode();
        gateNode.setId(templateNodeId);
        gateNode.setGraphTemplateId(graphTemplateId);
        gateNode.setLabel("review_gate");
        gateNode.setRequiredInputArtifacts(
                "[{\"template_node_label\":\"draft\",\"artifacts\":[{\"name\":\"draft.md\",\"description\":\"d\"}]}]");

        TemplateNode srcNode = new TemplateNode();
        srcNode.setId(srcNodeId);
        srcNode.setGraphTemplateId(graphTemplateId);
        srcNode.setLabel("draft");

        NodeExecution exec1 = new NodeExecution();
        exec1.setId(exec1Id);
        exec1.setTemplateNodeId(srcNodeId);
        exec1.setStatus(NodeExecutionStatus.completed);
        exec1.setIteration(1);

        NodeExecution exec2 = new NodeExecution();
        exec2.setId(exec2Id);
        exec2.setTemplateNodeId(srcNodeId);
        exec2.setStatus(NodeExecutionStatus.completed);
        exec2.setIteration(3); // higher iteration

        Mockito.when(templateNodeRepo.findById(templateNodeId)).thenReturn(Optional.of(gateNode));
        Mockito.when(templateNodeRepo.findByGraphTemplateId(graphTemplateId)).thenReturn(List.of(gateNode, srcNode));
        Mockito.when(nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(exec1, exec2));

        List<ResolvedArtifactGroup> result = service.resolveRequiredArtifacts(templateNodeId, runId);

        assertThat(result).isNotNull().hasSize(1);
        // Should select exec2 (higher iteration)
        assertThat(result.get(0).nodeExecutionId()).isEqualTo(exec2Id);
    }

    // --- resolveInputArtifactManifest: declared arm ---

    @Test
    void resolveInputArtifactManifest_joinsDeclaredNameOntoSourceOutputPrefix() {
        ManifestFixture f = new ManifestFixture();
        f.declare("[{\"template_node_label\":\"spec_review\",\"artifacts\":"
                + "[{\"name\":\"spec_and_plan.md\",\"description\":\"The approved spec\",\"required\":true}]}]");
        f.sourceOutputPrefix("system/runs/r/src-exec/out/");
        f.stub();

        InputArtifactManifest manifest = service.resolveInputArtifactManifest(f.runId, f.execId);

        assertThat(manifest.artifacts())
                .containsEntry("spec_review/spec_and_plan.md", "system/runs/r/src-exec/out/spec_and_plan.md");
        assertThat(manifest.required()).containsExactly("spec_review/spec_and_plan.md");
    }

    @Test
    void resolveInputArtifactManifest_treatsUnflaggedDeclarationAsOptional() {
        ManifestFixture f = new ManifestFixture();
        // No "required" key — the iteration-1 case, which must never harden into a pod abort.
        f.declare("[{\"template_node_label\":\"spec_review\",\"artifacts\":"
                + "[{\"name\":\"spec_review.md\",\"description\":\"Prior iteration's notes\"}]}]");
        f.sourceOutputPrefix("system/runs/r/src-exec/out/");
        f.stub();

        InputArtifactManifest manifest = service.resolveInputArtifactManifest(f.runId, f.execId);

        assertThat(manifest.artifacts()).containsKey("spec_review/spec_review.md");
        assertThat(manifest.required()).isEmpty();
    }

    @Test
    void resolveInputArtifactManifest_addsMissingSlashToOutputPrefix() {
        ManifestFixture f = new ManifestFixture();
        f.declare("[{\"template_node_label\":\"spec_review\",\"artifacts\":"
                + "[{\"name\":\"spec_and_plan.md\",\"description\":\"d\",\"required\":true}]}]");
        f.sourceOutputPrefix("system/runs/r/src-exec/out");
        f.stub();

        InputArtifactManifest manifest = service.resolveInputArtifactManifest(f.runId, f.execId);

        assertThat(manifest.artifacts())
                .containsEntry("spec_review/spec_and_plan.md", "system/runs/r/src-exec/out/spec_and_plan.md");
    }

    // --- resolveInputArtifactManifest: passthrough arm ---

    @Test
    void resolveInputArtifactManifest_materialisesAttachmentsFromTheGateThatRoutedHere() {
        ManifestFixture f = new ManifestFixture();
        f.gateRoutedHere(
                "approve_spec_and_plan",
                "{\"human_guidance.md\":\"system/runs/r/gate-attachments/g1/human_guidance.md\"}");
        f.stub();

        InputArtifactManifest manifest = service.resolveInputArtifactManifest(f.runId, f.execId);

        assertThat(manifest.artifacts())
                .containsEntry(
                        "approve_spec_and_plan/human_guidance.md",
                        "system/runs/r/gate-attachments/g1/human_guidance.md");
    }

    @Test
    void resolveInputArtifactManifest_ignoresGateThatDidNotRouteHere() {
        ManifestFixture f = new ManifestFixture();
        f.gateRoutedElsewhere(
                "some_other_gate", "{\"evidence.png\":\"system/runs/r/gate-attachments/g9/evidence.png\"}");
        f.stub();

        InputArtifactManifest manifest = service.resolveInputArtifactManifest(f.runId, f.execId);

        assertThat(manifest.artifacts()).isEmpty();
    }

    /**
     * Builds the smallest graph that exercises the manifest resolver: one target node, one declared
     * source, and one human gate with a single inbound edge into the target.
     */
    private class ManifestFixture {
        final UUID runId = UUID.randomUUID();
        final UUID execId = UUID.randomUUID();
        final UUID graphTemplateId = UUID.randomUUID();
        final UUID targetNodeId = UUID.randomUUID();
        final UUID sourceNodeId = UUID.randomUUID();
        final UUID sourceExecId = UUID.randomUUID();
        final UUID inboundEdgeId = UUID.randomUUID();
        final UUID otherEdgeId = UUID.randomUUID();

        final TemplateNode targetNode = new TemplateNode();
        final TemplateNode sourceNode = new TemplateNode();
        final NodeExecution targetExec = new NodeExecution();
        final NodeExecution sourceExec = new NodeExecution();
        final List<NodeExecution> completed = new ArrayList<>();

        ManifestFixture() {
            targetNode.setId(targetNodeId);
            targetNode.setGraphTemplateId(graphTemplateId);
            targetNode.setLabel("implement");

            sourceNode.setId(sourceNodeId);
            sourceNode.setGraphTemplateId(graphTemplateId);
            sourceNode.setLabel("spec_review");

            targetExec.setId(execId);
            targetExec.setWorkflowRunId(runId);
            targetExec.setTemplateNodeId(targetNodeId);
            targetExec.setStatus(NodeExecutionStatus.running);

            sourceExec.setId(sourceExecId);
            sourceExec.setWorkflowRunId(runId);
            sourceExec.setTemplateNodeId(sourceNodeId);
            sourceExec.setStatus(NodeExecutionStatus.completed);
            sourceExec.setIteration(1);
            completed.add(sourceExec);
        }

        void declare(String json) {
            targetNode.setRequiredInputArtifacts(json);
        }

        void sourceOutputPrefix(String prefix) {
            sourceExec.setArtifactRefs("{\"output\":\"" + prefix + "\"}");
        }

        void gateRoutedHere(String label, String artifactRefs) {
            addGate(label, artifactRefs, inboundEdgeId);
        }

        void gateRoutedElsewhere(String label, String artifactRefs) {
            addGate(label, artifactRefs, otherEdgeId);
        }

        private void addGate(String label, String artifactRefs, UUID traversedEdgeId) {
            NodeExecution gate = new NodeExecution();
            gate.setId(UUID.randomUUID());
            gate.setWorkflowRunId(runId);
            gate.setTemplateNodeId(UUID.randomUUID());
            gate.setStatus(NodeExecutionStatus.completed);
            gate.setIteration(1);
            gate.setLabel(label);
            gate.setReviewerType(ReviewerType.human);
            gate.setArtifactRefs(artifactRefs);
            gate.setTraversedEdgeIds(new UUID[] {traversedEdgeId});
            gate.setCompletedAt(Instant.now());
            completed.add(gate);
        }

        void stub() {
            WorkflowRun run = new WorkflowRun();
            run.setId(runId);

            String snapshot = "{\"edges\":[" + "{\"template_edge_id\":\""
                    + inboundEdgeId + "\",\"source_node_id\":\"" + sourceNodeId + "\",\"target_node_id\":\""
                    + targetNodeId + "\"}," + "{\"template_edge_id\":\""
                    + otherEdgeId + "\",\"source_node_id\":\"" + sourceNodeId + "\",\"target_node_id\":\""
                    + UUID.randomUUID() + "\"}" + "]}";

            Mockito.when(nodeExecutionRepo.findById(execId)).thenReturn(Optional.of(targetExec));
            Mockito.when(nodeExecutionRepo.findById(sourceExecId)).thenReturn(Optional.of(sourceExec));
            Mockito.when(templateNodeRepo.findById(targetNodeId)).thenReturn(Optional.of(targetNode));
            Mockito.when(templateNodeRepo.findByGraphTemplateId(graphTemplateId))
                    .thenReturn(List.of(targetNode, sourceNode));
            Mockito.when(nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                    .thenReturn(completed);
            Mockito.when(workflowRunRepo.findById(runId)).thenReturn(Optional.of(run));
            Mockito.when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
        }
    }
}
