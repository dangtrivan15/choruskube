package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.dto.SignalResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.GateClaimConflictException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Covers {@code story_version_recheck} on {@code RunService}: an approval at a Task Delivery gate is
 * refused while the Story's spec is under revision, and becomes the re-check route when the spec
 * moved since the run planned.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunServiceStoryVersionRecheckTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private WorkflowStub workflowStub;

    @Mock
    private WorkflowClientRegistry workflowClients;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private NodeExecutionClaimService nodeExecutionClaimService;

    @Mock
    private GateRecheckService gateRecheck;

    @Mock
    private GateFinalizeService gateFinalize;

    @Mock
    private UploadService uploadService;

    @Mock
    private StoragePrefixResolver storagePrefixResolver;

    @Mock
    private TaskRepository taskRepo;

    @Mock
    private StorySpecService storySpecService;

    @Mock
    private AuditSink auditSink;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID storyId = UUID.randomUUID();

    private static final String STORY_RECHECK_GATE =
            "{\"terminal_decisions\":[\"approved\"]," + "\"story_version_recheck\":{\"route\":\"rereview\"}}";

    private static final String PLAIN_GATE = "{\"terminal_decisions\":[\"approved\"]}";

    @BeforeEach
    void setUp() {
        lenient().when(workflowClients.clientFor(any())).thenReturn(workflowClient);
        service = new RunService(
                runRepo,
                execRepo,
                null, // edgeRepo
                snapshotBuilder,
                null, // graphTemplateRepo
                null, // templateNodeRepo
                null, // validationService
                null, // executionLogRepo
                objectMapper,
                eventPublisher,
                null, // gitRepoRepo
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(), // quotaService
                null, // placements
                workflowClients,
                null, // usageSink
                auditSink,
                storagePrefixResolver,
                null, // runPullRequestService
                null, // softwareProjectRepo
                null, // repoGroupMemberRepo
                null, // credentialPreflightChecker
                uploadService,
                taskRepo,
                null, // storyRepo
                null, // epicRepo
                null, // artifactResolutionService
                null, // applicationEventPublisher
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null, // roadmapCandidateMaterializer
                null, // roadmapCandidatesArtifactResolver
                nodeExecutionClaimService,
                null, // escalationContextResolver
                null, // roadmapProposalValidator
                null, // taskSettlement
                gateRecheck,
                gateFinalize,
                storySpecService);

        when(storagePrefixResolver.storagePrefixForRun(runId)).thenReturn("acme");
        when(uploadService.uploadGateText(eq("acme"), eq(runId), eq(nodeExecId), eq("human_guidance.md"), anyString()))
                .thenReturn("{\"human_guidance.md\":\"k\"}");
        Task task = new Task();
        task.setId(taskId);
        task.setStoryId(storyId);
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(storySpecService.openDesignRun(storyId)).thenReturn(Optional.empty());
    }

    private NodeExecution stubExec() {
        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        when(nodeExecutionClaimService.compareAndSetStatus(any(), any(), any())).thenReturn(1);
        return exec;
    }

    private WorkflowRun stubRun(String gateConfig, UUID runTaskId, Integer plannedVersion) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("test-workflow-id");
        run.setTaskId(runTaskId);
        run.setStorySpecVersion(plannedVersion);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        String snapshot = """
                {"nodes":[{"template_node_id":"%s","label":"final_approval","executor_type":"human","timeout_seconds":86400,"config_overrides":%s}],
                 "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"rereview"}]}""".formatted(templateNodeId, gateConfig, templateNodeId, UUID.randomUUID());
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
        when(workflowClient.newUntypedWorkflowStub("test-workflow-id")).thenReturn(workflowStub);
        return run;
    }

    private void currentVersion(int version) {
        StorySpecVersion spec = new StorySpecVersion();
        spec.setStoryId(storyId);
        spec.setVersion(version);
        spec.setContent("# Story spec v" + version);
        when(storySpecService.current(storyId)).thenReturn(Optional.of(spec));
    }

    private JsonNode signalled() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), payload.capture());
        return objectMapper.valueToTree(payload.getValue());
    }

    private SignalResponse approve() {
        return service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", "ship it", null, null));
    }

    @Test
    void approved_revisionOpen_conflictsWithoutClaim() {
        stubExec();
        stubRun(STORY_RECHECK_GATE, taskId, 1);
        currentVersion(1);
        WorkflowRun revision = new WorkflowRun();
        revision.setId(UUID.randomUUID());
        when(storySpecService.openDesignRun(storyId)).thenReturn(Optional.of(revision));

        assertThatThrownBy(this::approve)
                .isInstanceOf(ConflictException.class)
                .hasMessage(
                        "The Story spec is under revision (run " + revision.getId() + "); approve after it completes.");

        verify(nodeExecutionClaimService, never()).compareAndSetStatus(any(), any(), any());
        verify(workflowStub, never()).signal(anyString(), any());
        verify(uploadService, never()).overwriteRunInput(any(), any(), any());
        verify(runRepo, never()).setStorySpecVersion(any(), anyInt());
    }

    @Test
    void approved_versionMoved_becomesRouteWithGuidance() {
        stubExec();
        stubRun(STORY_RECHECK_GATE, taskId, 1);
        currentVersion(3);

        SignalResponse response = approve();

        assertThat(response.outcome()).isEqualTo(SignalResponse.SIGNALED);
        JsonNode payload = signalled();
        assertThat(payload.path("decision").asText()).isEqualTo("rereview");
        assertThat(payload.path("attachmentRefs").asText()).contains("human_guidance.md");
        String guidance = "The Story spec moved from v1 to v3 since this Task planned. Read the Revisions entries"
                + " for v2 through v3 in /workspace/in/run_input/story_spec.md and check this change against them.";
        verify(uploadService).uploadGateText("acme", runId, nodeExecId, "human_guidance.md", guidance);
        assertThat(payload.path("feedback").asText()).contains(guidance);
        verify(uploadService).overwriteRunInput(runId, "story_spec.md", "# Story spec v3");
        verify(runRepo).setStorySpecVersion(runId, 3);
        verifyNoInteractions(gateFinalize);
    }

    @Test
    void approved_versionMoved_rerouteFails_keepsThePlannedVersion() {
        stubExec();
        stubRun(STORY_RECHECK_GATE, taskId, 1);
        currentVersion(2);
        doThrow(new IllegalStateException("temporal down")).when(workflowStub).signal(anyString(), any());

        assertThatThrownBy(this::approve).hasMessageContaining("temporal down");

        verify(runRepo, never()).setStorySpecVersion(any(), anyInt());
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
    }

    @Test
    void approved_versionMoved_onAnAsyncGate_reroutesInsteadOfFinalizing() {
        NodeExecution exec = stubExec();
        exec.setFinalizeMode("async");
        stubRun(
                "{\"terminal_decisions\":[\"approved\"],\"merge_pull_requests\":\"squash\","
                        + "\"story_version_recheck\":{\"route\":\"rereview\"}}",
                taskId,
                1);
        currentVersion(2);

        approve();

        verify(gateFinalize, never()).requestApproval(any(), any(), any(), any(), any(), any());
        assertThat(signalled().path("decision").asText()).isEqualTo("rereview");
    }

    @Test
    void approved_sameVersion_proceeds() {
        stubExec();
        stubRun(STORY_RECHECK_GATE, taskId, 2);
        currentVersion(2);

        approve();

        assertThat(signalled().path("decision").asText()).isEqualTo("approved");
        verify(uploadService, never()).overwriteRunInput(any(), any(), any());
        verify(uploadService, never()).uploadGateText(any(), any(), any(), any(), any());
        verify(runRepo, never()).setStorySpecVersion(any(), anyInt());
    }

    @Test
    void nonApprovedDecision_unaffected() {
        stubExec();
        stubRun(STORY_RECHECK_GATE, taskId, 1);
        currentVersion(3);

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("rereview", "look again", null, null));

        assertThat(signalled().path("decision").asText()).isEqualTo("rereview");
        verifyNoInteractions(storySpecService);
        verify(uploadService, never()).overwriteRunInput(any(), any(), any());
    }

    @Test
    void gateWithoutConfig_unaffected() {
        stubExec();
        stubRun(PLAIN_GATE, taskId, 1);
        currentVersion(3);

        approve();

        assertThat(signalled().path("decision").asText()).isEqualTo("approved");
        verifyNoInteractions(storySpecService);
    }

    @Test
    void runWithoutTask_unaffected() {
        stubExec();
        stubRun(STORY_RECHECK_GATE, null, null);
        currentVersion(3);

        approve();

        assertThat(signalled().path("decision").asText()).isEqualTo("approved");
        verifyNoInteractions(storySpecService, taskRepo);
    }

    @Test
    void approved_gateNoLongerAwaiting_versionMoved_writesNothingAndIsRefusedByTheClaim() {
        NodeExecution exec = stubExec();
        exec.setStatus(NodeExecutionStatus.running);
        when(nodeExecutionClaimService.compareAndSetStatus(
                        nodeExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running))
                .thenReturn(0);
        stubRun(STORY_RECHECK_GATE, taskId, 1);
        currentVersion(2);

        assertThatThrownBy(this::approve).isInstanceOf(GateClaimConflictException.class);

        verify(uploadService, never()).overwriteRunInput(any(), any(), any());
        verify(uploadService, never()).uploadGateText(any(), any(), any(), any(), any());
        verify(runRepo, never()).setStorySpecVersion(any(), anyInt());
        verify(workflowStub, never()).signal(anyString(), any());
    }
}
