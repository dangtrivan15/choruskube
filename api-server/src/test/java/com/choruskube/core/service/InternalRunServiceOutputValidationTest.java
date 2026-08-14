package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.dto.InternalUpdateNodeExecutionRequest;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class InternalRunServiceOutputValidationTest {

    private InternalRunService service;
    private NodeExecutionRepository execRepo;
    private WorkflowRunRepository runRepo;
    private RunEventPublisher eventPublisher;
    private TemplateNodeRepository templateNodeRepo;
    private NodeDefinitionRepository nodeDefinitionRepo;
    private ArtifactService artifactService;

    @BeforeEach
    void setUp() {
        execRepo = Mockito.mock(NodeExecutionRepository.class);
        runRepo = Mockito.mock(WorkflowRunRepository.class);
        eventPublisher = Mockito.mock(RunEventPublisher.class);
        templateNodeRepo = Mockito.mock(TemplateNodeRepository.class);
        nodeDefinitionRepo = Mockito.mock(NodeDefinitionRepository.class);
        artifactService = Mockito.mock(ArtifactService.class);
        // templateNodeRepo returns empty by default → enforceOutputSpec exits early (no NPE)
        Mockito.when(templateNodeRepo.findById(Mockito.any())).thenReturn(Optional.empty());
        service = new InternalRunService(
                runRepo,
                execRepo,
                null,
                null,
                eventPublisher,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                templateNodeRepo,
                nodeDefinitionRepo,
                null,
                null, // taskRepo
                null, // epicRepo
                null,
                new DecisionOptionsResolver(),
                null,
                artifactService);
    }

    private NodeExecution stubExec(UUID id) {
        UUID runId = UUID.randomUUID();
        NodeExecution exec = new NodeExecution();
        exec.setId(id); // mirrors real execRepo.findById(id): the fetched entity carries its own id
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(UUID.randomUUID());
        exec.setGraphVersion(1);
        Mockito.when(execRepo.findById(id)).thenReturn(Optional.of(exec));

        // Stub the run for the new organizationId lookup in publishNodeStatusChanged
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        Mockito.when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        return exec;
    }

    @Test
    void rejectedWithNullResult_throws() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("rejected");

        var req = new InternalUpdateNodeExecutionRequest("completed", null, null, null, null, null);

        assertThatThrownBy(() -> service.updateNodeExecutionStatus(UUID.randomUUID(), execId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty result");
    }

    @Test
    void rejectedWithBlankResult_throws() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("rejected");

        var req = new InternalUpdateNodeExecutionRequest("completed", "   ", null, null, null, null);

        assertThatThrownBy(() -> service.updateNodeExecutionStatus(UUID.randomUUID(), execId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty result");
    }

    @Test
    void rejectedWithEmptyStringResult_throws() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("rejected");

        var req = new InternalUpdateNodeExecutionRequest("completed", "", null, null, null, null);

        assertThatThrownBy(() -> service.updateNodeExecutionStatus(UUID.randomUUID(), execId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty result");
    }

    @Test
    void completedWithEmptyResultAndNoRejection_succeeds() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("approved");
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        var req = new InternalUpdateNodeExecutionRequest("completed", "", null, null, null, null);

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
    }

    @Test
    void completedWithValidResult_succeeds() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        var req = new InternalUpdateNodeExecutionRequest("completed", "AI generated output", null, null, null, null);

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
    }

    @Test
    void failedWithEmptyResult_succeeds() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        var req = new InternalUpdateNodeExecutionRequest("failed", "", null, null, null, "Claude produced no result");

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
    }

    @Test
    void completedWithRequiredOutputSpec_warnMode_doesNotThrow() throws Exception {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        TemplateNode tn = new TemplateNode();
        tn.setNodeDefinitionId(UUID.randomUUID());
        Mockito.when(templateNodeRepo.findById(exec.getTemplateNodeId())).thenReturn(Optional.of(tn));

        NodeDefinition nd = new NodeDefinition();
        nd.setOutputSpec("{\"files\":[{\"name\":\"out.md\",\"required\":true}]}");
        Mockito.when(nodeDefinitionRepo.findById(tn.getNodeDefinitionId())).thenReturn(Optional.of(nd));

        // artifactRefs is null → missing artifacts, but warn mode should not throw
        var req = new InternalUpdateNodeExecutionRequest("completed", "AI output", null, null, null, null);

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
    }

    @Test
    void completedWithRequiredOutputSpec_enforceMode_throws() throws Exception {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        TemplateNode tn = new TemplateNode();
        tn.setNodeDefinitionId(UUID.randomUUID());
        Mockito.when(templateNodeRepo.findById(exec.getTemplateNodeId())).thenReturn(Optional.of(tn));

        NodeDefinition nd = new NodeDefinition();
        nd.setOutputSpec("{\"files\":[{\"name\":\"out.md\",\"required\":true}]}");
        Mockito.when(nodeDefinitionRepo.findById(tn.getNodeDefinitionId())).thenReturn(Optional.of(nd));

        // Set enforce mode via reflection
        Field field = InternalRunService.class.getDeclaredField("artifactEnforcementMode");
        field.setAccessible(true);
        field.set(service, "enforce");

        // artifactRefs is null → missing artifacts → enforce mode throws
        var req = new InternalUpdateNodeExecutionRequest("completed", "AI output", null, null, null, null);

        assertThatThrownBy(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void updateNodeExecutionStatus_acceptsPaused() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        var req = new InternalUpdateNodeExecutionRequest("paused", null, null, null, null, null);

        // Use exec.getWorkflowRunId() so that the runRepo.findById() mock (set up in
        // stubExec) resolves correctly — passing a freshly-created UUID would cause
        // a NotFoundException because stubExec only mocks the exec's own runId.
        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();

        Mockito.verify(execRepo)
                .save(Mockito.argThat(
                        e -> e.getStatus() == com.choruskube.core.model.enums.NodeExecutionStatus.paused));
    }

    private static final String ARTIFACT_REFS = "{\"output\":\"runs/r/e/out/\"}";

    @Test
    void escalateWithoutEscalationMd_throws() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("escalate");
        Mockito.when(artifactService.listArtifactNamesInternal(ARTIFACT_REFS))
                .thenReturn(java.util.List.of("review.md"));

        var req = new InternalUpdateNodeExecutionRequest("completed", "done", ARTIFACT_REFS, null, null, null);

        assertThatThrownBy(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("escalation.md");
    }

    @Test
    void escalateWithEscalationMd_passes() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("escalate");
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);
        Mockito.when(artifactService.listArtifactNamesInternal(ARTIFACT_REFS))
                .thenReturn(java.util.List.of("review.md", "escalation.md"));

        var req = new InternalUpdateNodeExecutionRequest("completed", "done", ARTIFACT_REFS, null, null, null);

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
    }

    /**
     * Regression test for a review finding: enforceOutputSpec must be driven by the artifactRefs
     * carried on *this* completion request, never by re-deriving it from a fresh execution
     * lookup. updateNodeExecutionStatus is not @Transactional and the app runs with
     * open-in-view=false, so an execId-keyed re-fetch (as ArtifactService.listArtifactNamesInternal
     * did before this fix) reads the last-*committed* row — which the agent's own completion
     * callback (artifact upload, then this call, in one request) has not written yet. The
     * persisted/pre-mutation execution here is stubbed with a null artifactRefs to stand in for
     * that lagging committed state; the escalation must still be accepted because the request's
     * artifactRefs is what actually gets checked.
     */
    @Test
    void escalateChecksTheRequestsArtifactRefsNotAFreshRepositoryLookup() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("escalate");
        exec.setArtifactRefs(null);
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);
        Mockito.when(artifactService.listArtifactNamesInternal(ARTIFACT_REFS))
                .thenReturn(java.util.List.of("escalation.md"));

        var req = new InternalUpdateNodeExecutionRequest("completed", "done", ARTIFACT_REFS, null, null, null);

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
        Mockito.verify(artifactService).listArtifactNamesInternal(ARTIFACT_REFS);
    }

    @Test
    void escalateWhenArtifactListingFails_failsClosedWith503() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("escalate");
        Mockito.when(artifactService.listArtifactNamesInternal(ARTIFACT_REFS))
                .thenThrow(new RuntimeException("Failed to list artifacts from object storage"));

        var req = new InternalUpdateNodeExecutionRequest("completed", "done", ARTIFACT_REFS, null, null, null);

        assertThatThrownBy(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getReason()).contains("unavailable");
                });
    }

    @Test
    void nonEscalateDecisionIsNotSubjectToEscalationMdCheck() {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        exec.setDecision("approved");
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        var req = new InternalUpdateNodeExecutionRequest("completed", "done", ARTIFACT_REFS, null, null, null);

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
        Mockito.verify(artifactService, Mockito.never()).listArtifactNamesInternal(Mockito.anyString());
    }

    @Test
    void completedWithEmptyFilesOutputSpec_doesNotEnforce() throws Exception {
        UUID execId = UUID.randomUUID();
        NodeExecution exec = stubExec(execId);
        Mockito.when(execRepo.save(Mockito.any())).thenReturn(exec);

        TemplateNode tn = new TemplateNode();
        tn.setNodeDefinitionId(UUID.randomUUID());
        Mockito.when(templateNodeRepo.findById(exec.getTemplateNodeId())).thenReturn(Optional.of(tn));

        NodeDefinition nd = new NodeDefinition();
        nd.setOutputSpec("{\"files\":[]}");
        Mockito.when(nodeDefinitionRepo.findById(tn.getNodeDefinitionId())).thenReturn(Optional.of(nd));

        var req = new InternalUpdateNodeExecutionRequest("completed", "AI output", null, null, null, null);

        assertThatCode(() -> service.updateNodeExecutionStatus(exec.getWorkflowRunId(), execId, req))
                .doesNotThrowAnyException();
    }
}
