package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.exception.PullRequestMergeException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the merge-on-approval contract on {@code RunService.signalHumanDecision}: on an
 * "approved" decision for a gate configured with {@code merge_pull_requests}, the run's registered
 * pull requests are merged, the outcome recorded, and a note appended — in that order, and before
 * the workflow is signalled. A merge failure must release the claim and never reach the
 * materializer or the signal.
 */
@ExtendWith(MockitoExtension.class)
class RunServicePullRequestMergeTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private TemplateEdgeRepository edgeRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private TemplateNodeRepository templateNodeRepo;

    @Mock
    private GraphValidationService validationService;

    @Mock
    private ExecutionLogRepository executionLogRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private WorkflowStub workflowStub;

    @Mock
    private RoadmapCandidateMaterializer roadmapCandidateMaterializer;

    @Mock
    private RoadmapCandidatesArtifactResolver roadmapCandidatesArtifactResolver;

    @Mock
    private NodeExecutionClaimService nodeExecutionClaimService;

    @Mock
    private WorkflowClientRegistry workflowClients;

    @Mock
    private RoadmapProposalValidator roadmapProposalValidator;

    @Mock
    private PullRequestMergeService pullRequestMergeService;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();

    private static final String MERGE_GATE_CONFIG =
            "{\"terminal_decisions\":[\"approved\"],\"merge_pull_requests\":\"squash\"}";

    /** Feature Development's Final Approval shape: a roadmap gate that also merges. */
    private static final String MERGE_AND_MATERIALIZE_GATE_CONFIG = "{\"terminal_decisions\":[\"approved\"],"
            + "\"materialize\":\"roadmap_extension\",\"merge_pull_requests\":\"squash\"}";

    @BeforeEach
    void setUp() {
        lenient().when(workflowClients.clientFor(any())).thenReturn(workflowClient);
        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                objectMapper,
                eventPublisher,
                gitRepoRepo,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(), // quotaService
                null, // placements
                workflowClients,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null, // storyRepo
                null, // epicRepo
                null,
                null,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                roadmapCandidateMaterializer,
                roadmapCandidatesArtifactResolver,
                nodeExecutionClaimService,
                null, // escalationContextResolver - unused (escalation not exercised)
                roadmapProposalValidator,
                pullRequestMergeService,
                null);
    }

    private NodeExecution stubExec() {
        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        lenient()
                .when(nodeExecutionClaimService.compareAndSetStatus(any(), any(), any()))
                .thenReturn(1);
        return exec;
    }

    private WorkflowRun stubRun(String nodeConfigOverridesJson) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("test-workflow-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        String configOverridesField =
                nodeConfigOverridesJson == null ? "" : ",\"config_overrides\":" + nodeConfigOverridesJson;
        String snapshot = """
                {"nodes":[{"template_node_id":"%s","label":"final_approval","executor_type":"human","timeout_seconds":86400%s}],
                 "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"rejected"}]}""".formatted(templateNodeId, configOverridesField, templateNodeId, UUID.randomUUID());
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
        lenient()
                .when(workflowClient.newUntypedWorkflowStub("test-workflow-id"))
                .thenReturn(workflowStub);
        return run;
    }

    private PullRequestMergeService.MergeOutcome sampleOutcome() {
        var ref = new PullRequestMergeService.PrRef(UUID.randomUUID(), "org/repo#1", "url", "org/repo", 1);
        return new PullRequestMergeService.MergeOutcome(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(
                        ref, PullRequestMergeService.Kind.MERGED, java.time.Instant.now(), "sha")));
    }

    @Test
    void approvedOnMergeGate_mergesThenRecordsThenSignals_inOrder() {
        stubExec();
        stubRun(MERGE_GATE_CONFIG);
        var outcome = sampleOutcome();
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash))
                .thenReturn(outcome);
        when(pullRequestMergeService.note(outcome))
                .thenReturn("Pull requests merged on approval (squash): org/repo#1.");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        InOrder inOrder = inOrder(pullRequestMergeService, workflowStub);
        inOrder.verify(pullRequestMergeService).mergeAll(runId, PullRequestMergeMethod.squash);
        inOrder.verify(pullRequestMergeService).recordOutcome(runId, outcome);
        inOrder.verify(workflowStub).signal(eq("human-decision-" + nodeExecId), any());
    }

    @Test
    void approvedOnMergeGate_signalPayloadResultContainsTheNote() {
        stubExec();
        stubRun(MERGE_GATE_CONFIG);
        var outcome = sampleOutcome();
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash))
                .thenReturn(outcome);
        when(pullRequestMergeService.note(outcome))
                .thenReturn("Pull requests merged on approval (squash): org/repo#1.");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), captor.capture());
        assertThat(captor.getValue().toString()).contains("Pull requests merged on approval (squash): org/repo#1.");
    }

    @Test
    void mergeException_releasesClaim_rethrows_andNeverCallsMaterializerOrSignal() {
        stubExec();
        stubRun(MERGE_GATE_CONFIG);
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash))
                .thenThrow(new PullRequestMergeException("Approval needs every pull request to be mergeable"));

        assertThatThrownBy(() ->
                        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null)))
                .isInstanceOf(PullRequestMergeException.class);

        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
        verifyNoInteractions(roadmapCandidateMaterializer);
        verifyNoInteractions(workflowStub);
        verify(pullRequestMergeService, never()).recordOutcome(any(), any());
    }

    @Test
    void nonApprovedDecision_neverCallsMergeService() {
        stubExec();
        stubRun(MERGE_GATE_CONFIG);

        // The stubbed snapshot's only edge condition is "rejected" — a merge-configured gate must
        // only merge on "approved", never on any other routed decision.
        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("rejected", null, null, null));

        verifyNoInteractions(pullRequestMergeService);
    }

    @Test
    void gateWithoutMergeKey_neverCallsMergeService() {
        stubExec();
        stubRun("{\"terminal_decisions\":[\"approved\"]}");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        verifyNoInteractions(pullRequestMergeService);
    }

    @Test
    void invalidRoadmapProposal_isRejectedBeforeAnythingIsMerged() {
        stubExec();
        stubRun(MERGE_AND_MATERIALIZE_GATE_CONFIG);
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(List.of(), List.of(), List.of());
        when(roadmapProposalValidator.validate(any(), any(), any(), any())).thenReturn(List.of("bad anchor"));

        assertThatThrownBy(() -> service.signalHumanDecision(
                        runId, nodeExecId, new SignalRequest("approved", null, null, edited)))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(pullRequestMergeService, roadmapCandidateMaterializer, workflowStub);
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
    }

    @Test
    void mergeAndMaterializeGate_mergesThenRecordsThenMaterializesThenSignals() {
        stubExec();
        stubRun(MERGE_AND_MATERIALIZE_GATE_CONFIG);
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(List.of(), List.of(), List.of());
        when(roadmapProposalValidator.validate(any(), any(), any(), any())).thenReturn(List.of());
        when(roadmapProposalValidator.summarize(edited))
                .thenReturn(new RoadmapProposalValidator.Summary(0, 0, 1, 0, 0));
        var outcome = sampleOutcome();
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash))
                .thenReturn(outcome);
        when(roadmapCandidateMaterializer.materialize(runId, edited, RoadmapMaterializeMode.roadmap_extension))
                .thenReturn(new MaterializationSummary(
                        List.of(), List.of(), List.of(UUID.randomUUID()), List.of(), 0, List.of()));

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, edited));

        // Materialization is the one non-idempotent write: it must follow every merge, so a merge
        // failure followed by a retry can never create the roadmap items twice.
        InOrder inOrder =
                inOrder(roadmapProposalValidator, pullRequestMergeService, roadmapCandidateMaterializer, workflowStub);
        inOrder.verify(roadmapProposalValidator).validate(any(), any(), any(), any());
        inOrder.verify(pullRequestMergeService).mergeAll(runId, PullRequestMergeMethod.squash);
        inOrder.verify(pullRequestMergeService).recordOutcome(runId, outcome);
        inOrder.verify(roadmapCandidateMaterializer)
                .materialize(runId, edited, RoadmapMaterializeMode.roadmap_extension);
        inOrder.verify(workflowStub).signal(eq("human-decision-" + nodeExecId), any());
    }

    @Test
    void signalFailureAfterMerging_releasesTheClaim() {
        stubExec();
        stubRun(MERGE_GATE_CONFIG);
        var outcome = sampleOutcome();
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash))
                .thenReturn(outcome);
        doThrow(new RuntimeException("temporal unavailable"))
                .when(workflowStub)
                .signal(eq("human-decision-" + nodeExecId), any());

        assertThatThrownBy(() ->
                        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null)))
                .hasMessageContaining("temporal unavailable");

        verify(pullRequestMergeService).recordOutcome(runId, outcome);
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
    }
}
