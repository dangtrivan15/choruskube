package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.dto.SignalResponse;
import com.choruskube.core.exception.GateRecheckUnavailableException;
import com.choruskube.core.exception.PullRequestMergeException;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.*;
import com.choruskube.core.service.GateDriftCheckService.DriftCheck;
import com.choruskube.core.service.GateDriftCheckService.Reason;
import com.choruskube.core.service.GateDriftCheckService.RepoDrift;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Covers {@code drift_recheck} on {@code RunService}: a stale approval is held and routed for a
 * re-check instead of merging, and a held approval is re-applied, held again, or voided once its
 * gate awaits a decision again.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RunServiceDriftRecheckTest {

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

    @Mock
    private GateDriftCheckService gateDriftCheck;

    @Mock
    private GateRecheckService gateRecheck;

    @Mock
    private UploadService uploadService;

    @Mock
    private StoragePrefixResolver storagePrefixResolver;

    @Mock
    private AuditSink auditSink;

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
                null, // usageSink
                auditSink,
                storagePrefixResolver,
                null,
                null,
                null,
                null,
                uploadService,
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
                null,
                gateDriftCheck,
                gateRecheck);
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
                 "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"rereview"}]}""".formatted(templateNodeId, configOverridesField, templateNodeId, UUID.randomUUID());
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

    private static final String RECHECK_GATE =
            "{\"terminal_decisions\":[\"approved\"],\"merge_pull_requests\":\"squash\","
                    + "\"drift_recheck\":{\"route\":\"rereview\",\"stamp\":\"always\"}}";

    private static final String RECHECK_AND_MATERIALIZE_GATE = "{\"terminal_decisions\":[\"approved\"],"
            + "\"materialize\":\"roadmap_extension\",\"merge_pull_requests\":\"squash\","
            + "\"drift_recheck\":{\"route\":\"rereview\",\"stamp\":\"always\"}}";

    private final UUID repoId = UUID.randomUUID();
    private GateStamp stamp;

    @BeforeEach
    void stampFixture() {
        stamp = new GateStamp();
        stamp.setId(UUID.randomUUID());
        stamp.setWorkflowRunId(runId);
        stamp.setGateTemplateNodeId(templateNodeId);
        stamp.setGateLabel("final_approval");
        stamp.setStampMode(StampMode.always);
        stamp.setRecheckRounds(1);
        stamp.setFeedback("ship it");
        stamp.setCreatedAt(Instant.now());
        when(storagePrefixResolver.storagePrefixForRun(runId)).thenReturn("acme");
        when(uploadService.uploadGateText(eq("acme"), eq(runId), eq(nodeExecId), eq("human_guidance.md"), anyString()))
                .thenReturn("{\"human_guidance.md\":\"k\"}");
        when(gateDriftCheck.guidance(eq(runId), any(), anyInt())).thenReturn("# Re-check");
    }

    private DriftCheck fresh() {
        return new DriftCheck(null, List.of());
    }

    private DriftCheck stale() {
        return new DriftCheck(
                null, List.of(new RepoDrift(repoId, "acme/app", "main", Reason.MAIN_MOVED, "abc1234", 2, List.of())));
    }

    private void stubHeld() {
        when(gateRecheck.findPending(stamp.getId())).thenReturn(Optional.of(stamp));
        NodeExecution gate = execRepo.findById(nodeExecId).orElseThrow();
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.awaiting_human))
                .thenReturn(List.of(gate));
        when(gateRecheck.reasonNotToReapply(eq(stamp), any())).thenReturn(Optional.empty());
    }

    private String signalled() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), payload.capture());
        return payload.getValue().toString();
    }

    @Test
    void freshRecheckGate_mergesAndSignalsApproved() {
        stubExec();
        stubRun(RECHECK_GATE);
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(fresh());
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash, null))
                .thenReturn(sampleOutcome());

        SignalResponse response =
                service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.SIGNALED);
        assertThat(signalled()).contains("approved");
        verify(gateRecheck, never()).hold(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleRecheckGate_holdsRoutesWithGuidanceAndMergesNothing() {
        stubExec();
        stubRun(RECHECK_GATE);
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(stale());
        when(gateRecheck.hold(
                        eq(runId),
                        any(),
                        eq("final_approval"),
                        eq(StampMode.always),
                        eq("ship it"),
                        eq("{\"spec.pdf\":\"k2\"}"),
                        isNull()))
                .thenReturn(stamp);

        SignalResponse response = service.signalHumanDecision(
                runId, nodeExecId, new SignalRequest("approved", "ship it", "{\"spec.pdf\":\"k2\"}", null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.HELD_FOR_RECHECK);
        assertThat(response.staleRepos())
                .extracting(SignalResponse.StaleRepo::repo)
                .containsExactly("acme/app");
        verify(pullRequestMergeService, never()).mergeAll(any(), any(), any());
        verify(pullRequestMergeService, never()).mergeAll(any(), any());
        verify(gateDriftCheck).guidance(eq(runId), any(), eq(1));
        assertThat(signalled()).contains("rereview").contains("human_guidance.md");
        verify(auditSink)
                .record(
                        eq(AuditSink.GATE_APPROVAL_HELD),
                        eq("workflow_run"),
                        eq(runId),
                        contains(stamp.getId().toString()));
    }

    @Test
    void gateWithoutDriftRecheck_neverChecks() {
        stubExec();
        stubRun(MERGE_GATE_CONFIG);
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash, null))
                .thenReturn(sampleOutcome());

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        verifyNoInteractions(gateDriftCheck);
    }

    @Test
    void recheckUnavailable_releasesClaimAndHoldsNothing() {
        stubExec();
        stubRun(RECHECK_GATE);
        when(gateDriftCheck.check(eq(runId), any()))
                .thenThrow(
                        new GateRecheckUnavailableException("acme/app", new RuntimeException("GitHub returned 502")));

        assertThatThrownBy(() ->
                        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null)))
                .isInstanceOf(GateRecheckUnavailableException.class);

        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
        verify(gateRecheck, never()).hold(any(), any(), any(), any(), any(), any(), any());
        verify(workflowStub, never()).signal(anyString(), any());
    }

    @Test
    void reviewerDecision_voidsAnyHeldApprovalForTheGate() {
        stubExec();
        stubRun(RECHECK_GATE);

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("rereview", "look again", null, null));

        verify(gateRecheck).voidPendingFor(runId, templateNodeId, GateRecheckService.REVIEWER_DECIDED);
    }

    @Test
    void heldApproval_freshAtReapply_mergesWithStoredFeedbackAndConsumes() {
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(fresh());
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash, stamp.getId()))
                .thenReturn(sampleOutcome());

        service.applyHeldApproval(stamp.getId());

        verify(pullRequestMergeService).mergeAll(runId, PullRequestMergeMethod.squash, stamp.getId());
        assertThat(signalled()).contains("approved").contains("ship it");
        verify(gateRecheck).consume(stamp.getId());
        verify(gateRecheck, never()).voidPendingFor(any(), any(), any());
    }

    @Test
    void heldApproval_staleAgainUnderTheCap_holdsAgain() {
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(stale());
        when(gateRecheck.holdAgain(stamp.getId())).thenReturn(stamp);

        service.applyHeldApproval(stamp.getId());

        verify(gateDriftCheck).guidance(eq(runId), any(), eq(2));
        verify(gateRecheck).holdAgain(stamp.getId());
        assertThat(signalled()).contains("rereview");
        verify(gateRecheck, never()).voidStamp(any(), any(), any());
    }

    @Test
    void heldApproval_staleAtTheCap_voidsAndParks() {
        stamp.setRecheckRounds(GateDriftCheckService.MAX_RECHECK_ROUNDS);
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(stale());

        service.applyHeldApproval(stamp.getId());

        verify(gateRecheck).voidStamp(eq(stamp.getId()), contains("2 re-check rounds"), eq(nodeExecId));
        verify(workflowStub, never()).signal(anyString(), any());
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
    }

    @Test
    void heldApproval_reasonNotToReapply_voidsWithoutClaiming() {
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateRecheck.reasonNotToReapply(eq(stamp), any())).thenReturn(Optional.of("escalated"));

        service.applyHeldApproval(stamp.getId());

        verify(gateRecheck).voidStamp(stamp.getId(), "escalated", nodeExecId);
        verify(nodeExecutionClaimService, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void heldApproval_claimLost_leavesStampAlone() {
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(nodeExecutionClaimService.compareAndSetStatus(
                        nodeExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running))
                .thenReturn(0);

        service.applyHeldApproval(stamp.getId());

        verify(gateRecheck, never()).voidStamp(any(), any(), any());
        verifyNoInteractions(pullRequestMergeService);
        verify(workflowStub, never()).signal(anyString(), any());
    }

    @Test
    void heldApproval_mergeRefused_voidsWithGitHubsReason() {
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(fresh());
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash, stamp.getId()))
                .thenThrow(new PullRequestMergeException("acme/app#1 has merge conflicts with main"));

        service.applyHeldApproval(stamp.getId());

        verify(gateRecheck).voidStamp(eq(stamp.getId()), contains("merge conflicts"), eq(nodeExecId));
        verify(gateRecheck, never()).consume(any());
    }

    @Test
    void heldApproval_noLongerPending_doesNothing() {
        when(gateRecheck.findPending(stamp.getId())).thenReturn(Optional.empty());

        service.applyHeldApproval(stamp.getId());

        verifyNoInteractions(nodeExecutionClaimService, pullRequestMergeService);
    }

    @Test
    void heldApproval_materializesTheApprovedDocument() {
        stubExec();
        stubRun(RECHECK_AND_MATERIALIZE_GATE);
        stubHeld();
        RoadmapCandidatesDocument approved = new RoadmapCandidatesDocument(List.of(), List.of(), List.of());
        when(gateRecheck.approvedCandidates(stamp)).thenReturn(approved);
        when(roadmapProposalValidator.validate(eq(runId), same(approved), any(), any()))
                .thenReturn(List.of());
        when(roadmapProposalValidator.summarize(approved))
                .thenReturn(new RoadmapProposalValidator.Summary(0, 0, 0, 0, 0));
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(fresh());
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash, stamp.getId()))
                .thenReturn(sampleOutcome());

        service.applyHeldApproval(stamp.getId());

        verify(roadmapCandidatesArtifactResolver, never()).resolve(any(UUID.class), any(UUID.class));
        verify(roadmapProposalValidator).validate(eq(runId), same(approved), any(), any());
        verify(gateRecheck).consume(stamp.getId());
    }

    private DriftCheck unrecorded() {
        return new DriftCheck(
                null, List.of(new RepoDrift(repoId, "acme/app", "main", Reason.NO_RECORD, null, 0, List.of())));
    }

    @Test
    void staleRecheckGate_writesTheStampBeforeSignalling() {
        stubExec();
        stubRun(RECHECK_GATE);
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(stale());
        when(gateRecheck.hold(any(), any(), any(), any(), any(), any(), any())).thenReturn(stamp);

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        InOrder order = inOrder(gateRecheck, workflowStub);
        order.verify(gateRecheck).hold(any(), any(), any(), any(), any(), any(), any());
        order.verify(workflowStub).signal(eq("human-decision-" + nodeExecId), any());
    }

    @Test
    void staleRecheckGate_signalFails_voidsTheNewStampAndReleasesTheClaim() {
        stubExec();
        stubRun(RECHECK_GATE);
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(stale());
        when(gateRecheck.hold(any(), any(), any(), any(), any(), any(), any())).thenReturn(stamp);
        doThrow(new IllegalStateException("temporal down")).when(workflowStub).signal(anyString(), any());

        assertThatThrownBy(() ->
                        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null)))
                .hasMessageContaining("temporal down");

        verify(gateRecheck).voidStamp(eq(stamp.getId()), contains("temporal down"), eq(nodeExecId));
        verify(nodeExecutionClaimService)
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
    }

    @Test
    void heldApproval_bookkeepingFailsAfterTheSignal_keepsTheClaimAndDoesNotPark() {
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(fresh());
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash, stamp.getId()))
                .thenReturn(sampleOutcome());
        doThrow(new IllegalStateException("db blip")).when(gateRecheck).consume(stamp.getId());

        service.applyHeldApproval(stamp.getId());

        assertThat(signalled()).contains("approved");
        verify(nodeExecutionClaimService, never())
                .compareAndSetStatus(nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
        verify(gateRecheck, never()).voidStamp(any(), any(), any());
    }

    @Test
    void heldApproval_isReappliedWithTheReviewersAttachments() {
        stamp.setAttachmentRefs("{\"spec.pdf\":\"k2\"}");
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(fresh());
        when(pullRequestMergeService.mergeAll(runId, PullRequestMergeMethod.squash, stamp.getId()))
                .thenReturn(sampleOutcome());

        service.applyHeldApproval(stamp.getId());

        assertThat(signalled()).contains("spec.pdf");
    }

    @Test
    void heldApproval_reposStillUnrecordedAfterARecheck_parksWithAnActionableReason() {
        stubExec();
        stubRun(RECHECK_GATE);
        stubHeld();
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(unrecorded());

        service.applyHeldApproval(stamp.getId());

        verify(gateRecheck).voidStamp(eq(stamp.getId()), contains("agent image"), eq(nodeExecId));
        verify(gateRecheck, never()).holdAgain(any());
        verify(workflowStub, never()).signal(anyString(), any());
    }

    @Test
    void reviewerApproval_unrecordedRepos_stillGetsOneRecheck() {
        stubExec();
        stubRun(RECHECK_GATE);
        when(gateDriftCheck.check(eq(runId), any())).thenReturn(unrecorded());
        when(gateRecheck.hold(any(), any(), any(), any(), any(), any(), any())).thenReturn(stamp);

        SignalResponse response =
                service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null, null));

        assertThat(response.outcome()).isEqualTo(SignalResponse.HELD_FOR_RECHECK);
    }
}
