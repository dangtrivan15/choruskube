package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ArtifactResolutionServiceTest {

    private TemplateNodeRepository templateNodeRepo;
    private NodeExecutionRepository nodeExecutionRepo;
    private ArtifactResolutionService service;

    @BeforeEach
    void setUp() {
        templateNodeRepo = Mockito.mock(TemplateNodeRepository.class);
        nodeExecutionRepo = Mockito.mock(NodeExecutionRepository.class);
        service = new ArtifactResolutionService(templateNodeRepo, nodeExecutionRepo, new ObjectMapper());
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
}
