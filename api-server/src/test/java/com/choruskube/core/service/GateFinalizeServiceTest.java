package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.dto.GateFinalizeDtos.CompareItem;
import com.choruskube.core.dto.GateFinalizeDtos.CompareResultItem;
import com.choruskube.core.dto.GateFinalizeDtos.HeldClaim;
import com.choruskube.core.dto.GateFinalizeDtos.IssueRequest;
import com.choruskube.core.dto.GateFinalizeDtos.IssueResultItem;
import com.choruskube.core.dto.GateFinalizeDtos.MaterializeResponse;
import com.choruskube.core.dto.GateFinalizeDtos.PlanResponse;
import com.choruskube.core.dto.GateFinalizeDtos.PrItem;
import com.choruskube.core.dto.GateFinalizeDtos.PrResultItem;
import com.choruskube.core.dto.GateFinalizeDtos.StepResult;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.dto.SignalResponse;
import com.choruskube.core.dto.TaskIssueTarget;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.GateClaimConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.DriftRecheckConfig;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.GateDriftCheckService.CompareInput;
import com.choruskube.core.service.GateDriftCheckService.CompareResult;
import com.choruskube.core.service.GateDriftCheckService.DriftCheck;
import com.choruskube.core.service.GateDriftCheckService.DriftInputs;
import com.choruskube.core.service.GateDriftCheckService.Reason;
import com.choruskube.core.service.GateDriftCheckService.RepoDrift;
import com.choruskube.core.service.PullRequestMergeService.Kind;
import com.choruskube.core.service.PullRequestMergeService.MergeOutcome;
import com.choruskube.core.service.PullRequestMergeService.MergePlan;
import com.choruskube.core.service.PullRequestMergeService.PrRef;
import com.choruskube.core.service.PullRequestMergeService.WorkerPrResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The steps that finish an approval on an asynchronous gate: the click that claims and records it,
 * the held approval the workflow re-applies as the gate opens, and plan / hold / complete / refuse,
 * each of which a Temporal retry may repeat after it already succeeded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GateFinalizeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final String REFS = "{\"spec.pdf\":\"k2\"}";
    private static final String GUIDANCE = "{\"human_guidance.md\":\"k\"}";

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private GateStampRepository stampRepo;

    @Mock
    private TaskRepository taskRepo;

    @Mock
    private TaskGithubIssueRepository taskGithubIssueRepo;

    @Mock
    private RunService runService;

    @Mock
    private GateRecheckService gateRecheck;

    @Mock
    private GateDriftCheckService drift;

    @Mock
    private PullRequestMergeService merge;

    @Mock
    private NodeExecutionClaimService claims;

    @Mock
    private UploadService uploads;

    @Mock
    private StoragePrefixResolver storagePrefixes;

    @Mock
    private RunEventPublisher events;

    @Mock
    private AuditSink auditSink;

    @Mock
    private PlatformTransactionManager txManager;

    private final SimpleTransactionStatus txStatus = new SimpleTransactionStatus();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID gateExecId = UUID.randomUUID();
    private final UUID earlierGateExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();
    private final UUID rowId = UUID.randomUUID();

    private GateFinalizeService service;
    private WorkflowRun run;
    private NodeExecution gate;
    private GateStamp stamp;

    private static final String RECHECK_GATE =
            "{\"terminal_decisions\":[\"approved\"],\"merge_pull_requests\":\"squash\","
                    + "\"drift_recheck\":{\"route\":\"rereview\",\"stamp\":\"always\"}}";
    private static final String MERGE_GATE =
            "{\"terminal_decisions\":[\"approved\"],\"merge_pull_requests\":\"squash\"}";
    private static final String MERGE_AND_MATERIALIZE_GATE = "{\"terminal_decisions\":[\"approved\"],"
            + "\"materialize\":\"roadmap_extension\",\"merge_pull_requests\":\"squash\"}";
    private static final String MATERIALIZE_GATE =
            "{\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_extension\"}";

    @BeforeEach
    void setUp() {
        service = new GateFinalizeService(
                runRepo,
                execRepo,
                stampRepo,
                taskRepo,
                taskGithubIssueRepo,
                runService,
                gateRecheck,
                drift,
                merge,
                claims,
                uploads,
                storagePrefixes,
                new DecisionOptionsResolver(),
                events,
                auditSink,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                txManager);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        gate = new NodeExecution();
        gate.setId(gateExecId);
        gate.setWorkflowRunId(runId);
        gate.setTemplateNodeId(templateNodeId);
        gate.setGraphVersion(1);
        gate.setStatus(NodeExecutionStatus.finalizing);
        gate.setFinalizeMode("async");
        gate.setResult("Tests passed");
        when(execRepo.findById(gateExecId)).thenReturn(Optional.of(gate));

        stamp = new GateStamp();
        stamp.setId(approvalId);
        stamp.setWorkflowRunId(runId);
        stamp.setGateTemplateNodeId(templateNodeId);
        stamp.setGateLabel("final_approval");
        stamp.setStampedNodeExecutionId(gateExecId);
        stamp.setStampMode(StampMode.always);
        stamp.setRecheckRounds(0);
        stamp.setStatus(GateStampStatus.finalizing);
        stamp.setFeedback("ship it");
        stamp.setAttachmentRefs(REFS);
        stamp.setCreatedAt(NOW.minusSeconds(60));
        when(stampRepo.findById(approvalId)).thenReturn(Optional.of(stamp));
        when(stampRepo.findLockedById(approvalId)).thenReturn(Optional.of(stamp));

        when(runService.validateDecisionAgainstEdges(any(), any(), anyString())).thenAnswer(inv -> inv.getArgument(2));
        when(storagePrefixes.storagePrefixForRun(runId)).thenReturn("acme");
        snapshot(RECHECK_GATE);
    }

    private JsonNode snapshot(String nodeConfig) {
        try {
            JsonNode snapshot =
                    objectMapper.readTree("""
                    {"nodes":[{"template_node_id":"%s","label":"final_approval","executor_type":"human","config_overrides":%s}],
                     "edges":[{"source_node_id":"%s","target_node_id":"%s","condition":"rereview"}]}""".formatted(templateNodeId, nodeConfig, templateNodeId, UUID.randomUUID()));
            when(runService.readSnapshot(run)).thenReturn(snapshot);
            return snapshot;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private GateStamp heldStamp() {
        GateStamp held = new GateStamp();
        held.setId(UUID.randomUUID());
        held.setWorkflowRunId(runId);
        held.setGateTemplateNodeId(templateNodeId);
        held.setGateLabel("final_approval");
        held.setStampedNodeExecutionId(earlierGateExecId);
        held.setStampMode(StampMode.always);
        held.setRecheckRounds(1);
        held.setStatus(GateStampStatus.pending);
        held.setCreatedAt(NOW.minusSeconds(600));
        return held;
    }

    private PrRef prRef() {
        return new PrRef(rowId, "acme/app#7", "https://github.com/acme/app/pull/7", "acme/app", 7);
    }

    private MergePlan mergePlan() {
        return new MergePlan(PullRequestMergeMethod.squash, List.of(), List.of(prRef()), List.of());
    }

    private DriftCheck staleCheck() {
        return new DriftCheck(
                null, List.of(new RepoDrift(repoId, "acme/app", "main", Reason.MAIN_MOVED, "abc1234", 2, List.of())));
    }

    private DriftInputs comparable() {
        return new DriftInputs(null, List.of(), List.of(new CompareInput(repoId, "acme/app", "main", "abc1234")));
    }

    // ── the approve click ───────────────────────────────────────────────────────

    @Test
    void requestApproval_claimsGate_recordsApproval_signalsWithItsId_repliesFinalizing() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing))
                .thenReturn(1);
        when(gateRecheck.begin(
                        eq(runId),
                        eq(gate),
                        eq("final_approval"),
                        eq(StampMode.if_unchanged),
                        eq("ship it"),
                        eq(REFS),
                        isNull()))
                .thenReturn(stamp);

        SignalResponse response = service.requestApproval(
                run,
                gate,
                new SignalRequest("approved", "ship it", REFS, null),
                runService.readSnapshot(run),
                new DriftRecheckConfig("rereview", StampMode.if_unchanged),
                null);

        assertThat(response.outcome()).isEqualTo(SignalResponse.FINALIZING);
        InOrder order = inOrder(claims, gateRecheck, stampRepo, runService, events);
        order.verify(claims)
                .compareAndSetStatus(gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing);
        order.verify(gateRecheck).voidPendingFor(runId, templateNodeId, GateRecheckService.REVIEWER_DECIDED);
        order.verify(stampRepo).flush();
        order.verify(gateRecheck).begin(any(), any(), any(), any(), any(), any(), any());
        order.verify(runService).signalGate(run, gateExecId, "approved", "ship it", REFS, approvalId);
        order.verify(events).publishNodeStatusChanged(runId, gateExecId, "finalizing");
    }

    @Test
    void requestApproval_signalFails_voidsApproval_releasesGate() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        when(claims.compareAndSetStatus(any(), any(), any())).thenReturn(1);
        when(gateRecheck.begin(any(), any(), any(), any(), any(), any(), any())).thenReturn(stamp);
        doThrow(new IllegalStateException("temporal unavailable"))
                .when(runService)
                .signalGate(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.requestApproval(
                        run,
                        gate,
                        new SignalRequest("approved", null, null, null),
                        runService.readSnapshot(run),
                        null,
                        null))
                .hasMessageContaining("temporal unavailable");

        // The reason is shown on the gate, so the exception's text stays in the log.
        verify(gateRecheck)
                .voidStamp(
                        eq(approvalId),
                        eq("Could not start finalizing the approval. Approve again to retry."),
                        eq(gateExecId));
        verify(claims)
                .compareAndSetStatus(gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human);
        verify(events, never()).publishNodeStatusChanged(runId, gateExecId, "finalizing");
    }

    @Test
    void requestApproval_gateAlreadyFinalizing_conflicts() {
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing))
                .thenReturn(0);

        assertThatThrownBy(() -> service.requestApproval(
                        run,
                        gate,
                        new SignalRequest("approved", null, null, null),
                        runService.readSnapshot(run),
                        null,
                        null))
                .isInstanceOf(GateClaimConflictException.class)
                .hasMessageContaining("already deciding");

        verify(gateRecheck, never()).begin(any(), any(), any(), any(), any(), any(), any());
        verify(runService, never()).signalGate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void requestApproval_voidsWhatAnEarlierExecutionLeftBehind_afterReplacingAHeldApproval_beforeRecording() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing))
                .thenReturn(1);
        when(gateRecheck.begin(any(), any(), any(), any(), any(), any(), any())).thenReturn(stamp);

        service.requestApproval(
                run, gate, new SignalRequest("approved", null, null, null), runService.readSnapshot(run), null, null);

        InOrder order = inOrder(gateRecheck, stampRepo);
        order.verify(gateRecheck).voidPendingFor(runId, templateNodeId, GateRecheckService.REVIEWER_DECIDED);
        order.verify(gateRecheck).voidLeftBehind(runId, templateNodeId, gateExecId);
        order.verify(stampRepo).flush();
        order.verify(gateRecheck).begin(any(), any(), any(), any(), any(), any(), any());
    }

    // ── a held approval, re-applied as its gate opens ─────────────────────────────

    @Test
    void claimHeld_reasonNotToReapply_voidsAndReturnsEmpty() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        GateStamp held = heldStamp();
        when(gateRecheck.findHeldFor(runId, templateNodeId)).thenReturn(Optional.of(held));
        when(gateRecheck.reasonNotToReapply(eq(held), any())).thenReturn(Optional.of("escalated meanwhile"));

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isNull();
        verify(gateRecheck).voidStamp(held.getId(), "escalated meanwhile", gateExecId);
        verify(claims, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void claimHeld_losesToAReviewerClaim_returnsEmpty() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        GateStamp held = heldStamp();
        when(gateRecheck.findHeldFor(runId, templateNodeId)).thenReturn(Optional.of(held));
        when(gateRecheck.reasonNotToReapply(eq(held), any())).thenReturn(Optional.empty());
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing))
                .thenReturn(0);

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isNull();
        verify(gateRecheck, never()).claimHeld(any(), any());
        verify(gateRecheck, never()).voidStamp(any(), any(), any());
    }

    @Test
    void claimHeld_success_flipsGateAndStamp() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        GateStamp held = heldStamp();
        when(gateRecheck.findHeldFor(runId, templateNodeId)).thenReturn(Optional.of(held));
        when(gateRecheck.reasonNotToReapply(eq(held), any())).thenReturn(Optional.empty());
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing))
                .thenReturn(1);
        when(gateRecheck.claimHeld(runId, templateNodeId)).thenReturn(Optional.of(held));

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isEqualTo(held.getId());
        verify(events).publishNodeStatusChanged(runId, gateExecId, "finalizing");
    }

    @Test
    void claimHeld_stampAlreadyGone_rollsTheGateClaimBack() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        GateStamp held = heldStamp();
        when(gateRecheck.findHeldFor(runId, templateNodeId)).thenReturn(Optional.of(held));
        when(gateRecheck.reasonNotToReapply(eq(held), any())).thenReturn(Optional.empty());
        when(claims.compareAndSetStatus(any(), any(), any())).thenReturn(1);
        when(gateRecheck.claimHeld(runId, templateNodeId)).thenReturn(Optional.empty());

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isNull();
        assertThat(txStatus.isRollbackOnly()).isTrue();
        verify(events, never()).publishNodeStatusChanged(any(), any(), any());
    }

    private RunService.StoryVersionStale storyUnderRevision(UUID revisionRunId) {
        return new RunService.StoryVersionStale(revisionRunId, "rereview", 0, null);
    }

    private RunService.StoryVersionStale storyMoved(int planned, int current) {
        StorySpecVersion spec = new StorySpecVersion();
        spec.setVersion(current);
        spec.setContent("# Story spec v" + current);
        return new RunService.StoryVersionStale(null, "rereview", planned, spec);
    }

    private GateStamp stubHeldAtOpenGate() {
        gate.setStatus(NodeExecutionStatus.awaiting_human);
        GateStamp held = heldStamp();
        when(gateRecheck.findHeldFor(runId, templateNodeId)).thenReturn(Optional.of(held));
        when(gateRecheck.reasonNotToReapply(eq(held), any())).thenReturn(Optional.empty());
        return held;
    }

    @Test
    void claimHeld_storyUnderRevision_voidsWithTheRevisionAndReturnsEmpty() {
        GateStamp held = stubHeldAtOpenGate();
        UUID revisionRunId = UUID.randomUUID();
        when(runService.storyVersionStale(eq(run), eq(templateNodeId), any()))
                .thenReturn(Optional.of(storyUnderRevision(revisionRunId)));

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isNull();
        verify(gateRecheck)
                .voidStamp(
                        held.getId(),
                        "The Story spec is under revision (run " + revisionRunId + "); approve after it completes.",
                        gateExecId);
        verify(events).publishNodeStatusChanged(runId, gateExecId, "awaiting_human");
        verify(claims, never()).compareAndSetStatus(any(), any(), any());
        verify(gateRecheck, never()).claimHeld(any(), any());
    }

    @Test
    void claimHeld_storyVersionMoved_voidsWithTheVersionsAndReturnsEmpty() {
        GateStamp held = stubHeldAtOpenGate();
        when(runService.storyVersionStale(eq(run), eq(templateNodeId), any()))
                .thenReturn(Optional.of(storyMoved(1, 2)));

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isNull();
        verify(gateRecheck)
                .voidStamp(held.getId(), "The Story spec moved from v1 to v2 since this Task planned.", gateExecId);
        verify(events).publishNodeStatusChanged(runId, gateExecId, "awaiting_human");
        verify(claims, never()).compareAndSetStatus(any(), any(), any());
        verify(gateRecheck, never()).claimHeld(any(), any());
    }

    @Test
    void claimHeld_storyVersionUnchanged_stillClaims() {
        GateStamp held = stubHeldAtOpenGate();
        when(runService.storyVersionStale(eq(run), eq(templateNodeId), any())).thenReturn(Optional.empty());
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing))
                .thenReturn(1);
        when(gateRecheck.claimHeld(runId, templateNodeId)).thenReturn(Optional.of(held));

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isEqualTo(held.getId());
        verify(runService).storyVersionStale(eq(run), eq(templateNodeId), any());
        verify(gateRecheck, never()).voidStamp(any(), any(), any());
    }

    @Test
    void claimHeld_retryAfterItsOwnClaim_returnsTheSameApproval() {
        GateStamp claimed = heldStamp();
        claimed.setStatus(GateStampStatus.finalizing);
        when(gateRecheck.findFinalizingFor(runId, templateNodeId)).thenReturn(Optional.of(claimed));

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isEqualTo(claimed.getId());
        verify(claims, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void claimHeld_reviewersApprovalInFlight_returnsEmpty() {
        when(gateRecheck.findFinalizingFor(runId, templateNodeId)).thenReturn(Optional.of(stamp));

        HeldClaim claim = service.claimHeld(runId, gateExecId);

        assertThat(claim.approvalId()).isNull();
        verify(claims, never()).compareAndSetStatus(any(), any(), any());
        verify(gateRecheck, never()).claimHeld(any(), any());
    }

    // ── plan ────────────────────────────────────────────────────────────────────

    @Test
    void plan_invalidRoadmap_refused() {
        snapshot(MERGE_AND_MATERIALIZE_GATE);
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        when(gateRecheck.approvedCandidates(stamp)).thenReturn(doc);
        when(runService.prepareMaterialization(runId, RoadmapMaterializeMode.roadmap_extension, doc))
                .thenThrow(new ValidationException(List.of("epics[0]: title is required for a new item")));

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.REFUSED);
        assertThat(plan.reason()).contains("epics[0]: title is required for a new item");
        verify(merge, never()).planMerge(any(), any());
    }

    @Test
    void plan_knownStale_hold() {
        when(drift.gather(eq(runId), eq(templateNodeId), any()))
                .thenReturn(new DriftInputs(
                        null,
                        List.of(new RepoDrift(repoId, "acme/app", "main", Reason.CONFLICT, null, 0, List.of("a.go"))),
                        List.of()));

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.HOLD);
        verify(merge, never()).planMerge(any(), any());
    }

    @Test
    void plan_unresolvablePr_refused() {
        snapshot(MERGE_GATE);
        when(merge.planMerge(runId, PullRequestMergeMethod.squash))
                .thenReturn(new MergePlan(
                        PullRequestMergeMethod.squash,
                        List.of(),
                        List.of(),
                        List.of("https://example.test/x: cannot determine its repository or number")));

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.REFUSED);
        assertThat(plan.reason()).contains("cannot determine its repository or number");
        assertThat(plan.prs()).isEmpty();
    }

    @Test
    void plan_proceed_listsComparesAndPrs() {
        when(drift.gather(eq(runId), eq(templateNodeId), any())).thenReturn(comparable());
        when(merge.planMerge(runId, PullRequestMergeMethod.squash)).thenReturn(mergePlan());

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.PROCEED);
        assertThat(plan.reason()).isNull();
        assertThat(plan.compares()).containsExactly(new CompareItem(repoId, "acme/app", "main", "abc1234"));
        assertThat(plan.prs())
                .containsExactly(new PrItem(rowId, "acme/app", 7, "acme/app#7", "https://github.com/acme/app/pull/7"));
        assertThat(plan.mergeMethod()).isEqualTo("squash");
    }

    @Test
    void plan_voidedApproval_refusedWithItsReason() {
        stamp.setStatus(GateStampStatus.voided);
        stamp.setVoidReason("Could not start finalizing: temporal unavailable");

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.REFUSED);
        assertThat(plan.reason()).isEqualTo("Could not start finalizing: temporal unavailable");
        verify(drift, never()).gather(any(), any(), any());
    }

    @Test
    void plan_storyVersionMoved_refusedWithItsReason() {
        when(runService.storyVersionStale(eq(run), eq(templateNodeId), any()))
                .thenReturn(Optional.of(storyMoved(1, 2)));

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.REFUSED);
        assertThat(plan.reason()).isEqualTo("The Story spec moved from v1 to v2 since this Task planned.");
        verify(drift, never()).gather(any(), any(), any());
        verify(merge, never()).planMerge(any(), any());
    }

    @Test
    void plan_approvalOfAnotherRun_isNotFound() {
        stamp.setWorkflowRunId(UUID.randomUUID());

        assertThatThrownBy(() -> service.plan(runId, gateExecId, approvalId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void plan_executionOfAnotherRun_isNotFound() {
        gate.setWorkflowRunId(UUID.randomUUID());

        assertThatThrownBy(() -> service.plan(runId, gateExecId, approvalId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void plan_gateNotMarkedAsync_isNotFound() {
        gate.setFinalizeMode(null);

        assertThatThrownBy(() -> service.plan(runId, gateExecId, approvalId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void plan_roadmapGate_declaresWritesRoadmap() {
        snapshot(MATERIALIZE_GATE);
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        when(gateRecheck.approvedCandidates(stamp)).thenReturn(doc);
        when(runService.prepareMaterialization(runId, RoadmapMaterializeMode.roadmap_extension, doc))
                .thenReturn(RunService.MaterializationPlan.runnable(RoadmapMaterializeMode.roadmap_extension, doc));

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.PROCEED);
        assertThat(plan.writesRoadmap()).isTrue();
    }

    @Test
    void plan_nothingToCreate_doesNotDeclare() {
        snapshot(MATERIALIZE_GATE);
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        when(gateRecheck.approvedCandidates(stamp)).thenReturn(doc);
        when(runService.prepareMaterialization(runId, RoadmapMaterializeMode.roadmap_extension, doc))
                .thenReturn(RunService.MaterializationPlan.fixedNote("Roadmap proposal: nothing to create"));

        PlanResponse plan = service.plan(runId, gateExecId, approvalId);

        assertThat(plan.outcome()).isEqualTo(GateFinalizeService.PROCEED);
        assertThat(plan.writesRoadmap()).isFalse();
    }

    // ── materialize ─────────────────────────────────────────────────────────────

    @Test
    void materialize_recordsMerges_claimsOnce_returnsRequests_neverFilesOnTheApi() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        UUID taskId = UUID.randomUUID();
        UUID targetRepoId = UUID.randomUUID();
        when(runService.materializeForWorker(eq(runId), any()))
                .thenReturn(new RunService.MaterializationOutcome(
                        "Roadmap extension approved: created 1 Epics",
                        List.of(new TaskIssueTarget(taskId, targetRepoId, "acme/app", "Task 1", "body"))));

        MaterializeResponse response = service.materialize(runId, gateExecId, approvalId, mergedRow());

        assertThat(response.since()).isEqualTo(NOW);
        assertThat(response.issues())
                .containsExactly(new IssueRequest(taskId, targetRepoId, "acme/app", "Task 1", "body"));
        verify(merge).recordOutcome(eq(runId), any());
        verify(runService).materializeForWorker(eq(runId), any());
        verify(runService, never()).materializeAndNote(any(), any());
        assertThat(stamp.getMaterializedAt()).isEqualTo(NOW);
        assertThat(stamp.getMaterializationNote()).isEqualTo("Roadmap extension approved: created 1 Epics");
    }

    @Test
    void materialize_repeat_returnsStoredRequests_writesNothing() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        when(runService.materializeForWorker(eq(runId), any()))
                .thenReturn(new RunService.MaterializationOutcome(
                        "note",
                        List.of(new TaskIssueTarget(
                                UUID.randomUUID(), UUID.randomUUID(), "acme/app", "Task 1", "body"))));

        MaterializeResponse first = service.materialize(runId, gateExecId, approvalId, mergedRow());
        MaterializeResponse second = service.materialize(runId, gateExecId, approvalId, mergedRow());

        assertThat(second).isEqualTo(first);
        verify(runService, times(1)).materializeForWorker(eq(runId), any());
    }

    @Test
    void materialize_claimedButNothingStored_returnsStoppedNoteAndNoIssues() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        stamp.setMaterializedAt(NOW.minusSeconds(5));

        MaterializeResponse response = service.materialize(runId, gateExecId, approvalId, mergedRow());

        assertThat(response.since()).isEqualTo(NOW.minusSeconds(5));
        assertThat(response.issues()).isEmpty();
        assertThat(stamp.getMaterializationNote())
                .isEqualTo("Writing the approved roadmap stopped partway, so some items may be missing.");
        verify(runService, never()).materializeForWorker(any(), any());
    }

    @Test
    void materialize_gateWithoutRoadmap_storesEmpty() {
        snapshot(MERGE_GATE);
        MergePlan plan = mergePlan();
        when(merge.planMerge(runId, PullRequestMergeMethod.squash)).thenReturn(plan);
        MergeOutcome outcome = new MergeOutcome(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(prRef(), Kind.MERGED, NOW, "m1")));
        when(merge.outcomeOf(runId, plan, List.of(new WorkerPrResult(rowId, Kind.MERGED, NOW, "m1")), approvalId))
                .thenReturn(outcome);

        MaterializeResponse response = service.materialize(runId, gateExecId, approvalId, mergedRow());

        assertThat(response.since()).isNull();
        assertThat(response.issues()).isEmpty();
        assertThat(stamp.getMaterializationNote()).isNull();
        assertThat(stamp.getIssueRequests()).isEqualTo("[]");
        verify(runService, never()).prepareMaterialization(any(), any(), any());
    }

    @Test
    void materialize_notFinalizing_conflicts() {
        stubMergeAndMaterialize(null);
        stamp.setStatus(GateStampStatus.voided);

        assertThatThrownBy(() -> service.materialize(runId, gateExecId, approvalId, mergedRow()))
                .isInstanceOf(ConflictException.class);

        verify(merge).recordOutcome(eq(runId), any());
        verify(runService, never()).materializeForWorker(any(), any());
    }

    @Test
    void materialize_losingStoreOnce_returnsTheWinnersRequests() throws Exception {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        UUID winnerTaskId = UUID.randomUUID();
        UUID winnerRepoId = UUID.randomUUID();
        String winnerJson = objectMapper.writeValueAsString(
                List.of(new IssueRequest(winnerTaskId, winnerRepoId, "acme/app", "Winner", "b")));
        when(runService.materializeForWorker(eq(runId), any())).thenAnswer(inv -> {
            // Simulates another overlapping attempt finishing and storing first, between this
            // attempt's claim and its own store-once.
            stamp.setMaterializationNote("written by the other attempt");
            stamp.setIssueRequests(winnerJson);
            return new RunService.MaterializationOutcome(
                    "ignored",
                    List.of(new TaskIssueTarget(UUID.randomUUID(), UUID.randomUUID(), "acme/app", "Mine", "b")));
        });

        MaterializeResponse response = service.materialize(runId, gateExecId, approvalId, mergedRow());

        assertThat(response.issues())
                .containsExactly(new IssueRequest(winnerTaskId, winnerRepoId, "acme/app", "Winner", "b"));
        assertThat(stamp.getMaterializationNote()).isEqualTo("written by the other attempt");
    }

    @Test
    void materialize_longBody_isCutToGitHubsLimit() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        String longBody = "x".repeat(70_000);
        UUID taskId = UUID.randomUUID();
        UUID targetRepoId = UUID.randomUUID();
        when(runService.materializeForWorker(eq(runId), any()))
                .thenReturn(new RunService.MaterializationOutcome(
                        "note", List.of(new TaskIssueTarget(taskId, targetRepoId, "acme/app", "Task 1", longBody))));

        MaterializeResponse response = service.materialize(runId, gateExecId, approvalId, mergedRow());

        assertThat(response.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.body()).hasSize(65_000);
            assertThat(issue.body()).isEqualTo(longBody.substring(0, 65_000));
        });
    }

    @Test
    void materialize_overTheHandoverBudget_returnsALeadingRun_storesAll() throws Exception {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        List<TaskIssueTarget> targets = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            targets.add(
                    new TaskIssueTarget(UUID.randomUUID(), UUID.randomUUID(), "acme/app", "Task " + i, "x".repeat(50)));
        }
        when(runService.materializeForWorker(eq(runId), any()))
                .thenReturn(new RunService.MaterializationOutcome("note", targets));

        MaterializeResponse response = service.materialize(runId, gateExecId, approvalId, mergedRow());

        assertThat(response.issues()).isNotEmpty();
        assertThat(response.issues().size()).isLessThan(targets.size());
        List<IssueRequest> stored = objectMapper.readValue(
                stamp.getIssueRequests(), new com.fasterxml.jackson.core.type.TypeReference<List<IssueRequest>>() {});
        assertThat(stored).hasSize(targets.size());
    }

    // ── hold ────────────────────────────────────────────────────────────────────

    @Test
    void hold_underCap_writesGuidance_setsRouteDecision_holdsStamp() {
        DriftInputs inputs = comparable();
        when(drift.gather(eq(runId), eq(templateNodeId), any())).thenReturn(inputs);
        when(drift.classify(eq(inputs), any())).thenReturn(staleCheck());
        when(drift.guidance(eq(runId), any(), eq(1))).thenReturn("# Re-check");
        when(uploads.uploadGateText("acme", runId, gateExecId, "human_guidance.md", "# Re-check"))
                .thenReturn(GUIDANCE);
        GateStamp heldAfter = heldStamp();
        heldAfter.setId(approvalId);
        when(gateRecheck.holdFromFinalizing(approvalId)).thenReturn(heldAfter);

        StepResult result =
                service.hold(runId, gateExecId, approvalId, List.of(new CompareResultItem(repoId, 2, false)));

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.HELD);
        assertThat(result.attachmentRefs()).isEqualTo(GUIDANCE);
        assertThat(result.result())
                .startsWith("Tests passed\n\n")
                .contains("round 1 of 2")
                .contains("acme/app (2 commits behind main)");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CompareResult>> compares = ArgumentCaptor.forClass(List.class);
        verify(drift).classify(eq(inputs), compares.capture());
        assertThat(compares.getValue()).containsExactly(new CompareResult(repoId, 2, false));
        verify(gateRecheck).holdFromFinalizing(approvalId);
        assertThat(gate.getDecision()).isEqualTo("rereview");
        assertThat(gate.getResult()).isEqualTo(result.result());
        assertThat(gate.getArtifactRefs()).isEqualTo(GUIDANCE);
        verify(execRepo).save(gate);
        verify(auditSink)
                .record(
                        eq(AuditSink.GATE_APPROVAL_HELD),
                        eq("workflow_run"),
                        eq(runId),
                        contains(approvalId.toString()));
    }

    @Test
    void hold_atCap_refused() {
        stamp.setRecheckRounds(2);
        when(drift.gather(eq(runId), eq(templateNodeId), any())).thenReturn(comparable());
        when(drift.classify(any(), any())).thenReturn(staleCheck());
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human))
                .thenReturn(1);

        StepResult result =
                service.hold(runId, gateExecId, approvalId, List.of(new CompareResultItem(repoId, 2, false)));

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.REFUSED);
        assertThat(result.reason()).contains("2 re-check rounds").contains("acme/app");
        verify(gateRecheck).voidStamp(eq(approvalId), contains("2 re-check rounds"), eq(gateExecId));
        verify(claims)
                .compareAndSetStatus(gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human);
        verify(uploads, never()).uploadGateText(any(), any(), any(), any(), any());
        verify(gateRecheck, never()).holdFromFinalizing(any());
    }

    @Test
    void hold_heldApprovalStillWithoutARecord_refusesInsteadOfHoldingAgain() {
        stamp.setRecheckRounds(1);
        when(drift.gather(eq(runId), eq(templateNodeId), any())).thenReturn(comparable());
        when(drift.classify(any(), any()))
                .thenReturn(new DriftCheck(
                        null,
                        List.of(new RepoDrift(repoId, "acme/app", "main", Reason.NO_RECORD, null, 0, List.of()))));
        when(claims.compareAndSetStatus(any(), any(), any())).thenReturn(1);

        StepResult result = service.hold(runId, gateExecId, approvalId, List.of());

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.REFUSED);
        assertThat(result.reason()).contains("could not tell which main commit was tested");
        verify(gateRecheck, never()).holdFromFinalizing(any());
    }

    @Test
    void hold_retryAfterHolding_returnsTheSameHeldResult() {
        stamp.setStatus(GateStampStatus.pending);
        stamp.setRecheckRounds(1);
        gate.setDecision("rereview");
        gate.setResult("Tests passed\n\nApproval held for a re-check against current main (round 1 of 2): x.");
        gate.setArtifactRefs(GUIDANCE);

        StepResult result = service.hold(runId, gateExecId, approvalId, List.of());

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.HELD);
        assertThat(result.result()).isEqualTo(gate.getResult());
        assertThat(result.attachmentRefs()).isEqualTo(GUIDANCE);
        verify(gateRecheck, never()).holdFromFinalizing(any());
        verify(uploads, never()).uploadGateText(any(), any(), any(), any(), any());
    }

    @Test
    void hold_retryAfterRefusing_refusesAgainWithoutChangingAnything() {
        stamp.setStatus(GateStampStatus.voided);
        stamp.setVoidReason("The default branch moved again after 2 re-check rounds: x");

        StepResult result = service.hold(runId, gateExecId, approvalId, List.of());

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.REFUSED);
        assertThat(result.reason()).isEqualTo(stamp.getVoidReason());
        verify(gateRecheck, never()).voidStamp(any(), any(), any());
        verify(claims, never()).compareAndSetStatus(any(), any(), any());
    }

    // ── complete ────────────────────────────────────────────────────────────────

    private void stubMergeAndMaterialize(RoadmapCandidatesDocument doc) {
        snapshot(MERGE_AND_MATERIALIZE_GATE);
        MergePlan plan = mergePlan();
        when(merge.planMerge(runId, PullRequestMergeMethod.squash)).thenReturn(plan);
        MergeOutcome outcome = new MergeOutcome(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(prRef(), Kind.MERGED, NOW, "m1")));
        when(merge.outcomeOf(runId, plan, List.of(new WorkerPrResult(rowId, Kind.MERGED, NOW, "m1")), approvalId))
                .thenReturn(outcome);
        when(merge.note(outcome)).thenReturn("Pull requests merged on approval (squash): acme/app#7.");
        when(gateRecheck.approvedCandidates(stamp)).thenReturn(doc);
        when(runService.prepareMaterialization(runId, RoadmapMaterializeMode.roadmap_extension, doc))
                .thenReturn(RunService.MaterializationPlan.runnable(RoadmapMaterializeMode.roadmap_extension, doc));
        when(runService.materializeAndNote(eq(runId), any()))
                .thenReturn("Roadmap extension approved: created 1 Epics, 0 Stories, 0 Tasks and 0 dependency edges "
                        + "(0 skipped)");
        doAnswer(inv -> {
                    stamp.setStatus(GateStampStatus.consumed);
                    return null;
                })
                .when(gateRecheck)
                .consume(approvalId);
    }

    private List<PrResultItem> mergedRow() {
        return List.of(new PrResultItem(rowId, "MERGED", NOW, "m1"));
    }

    @Test
    void complete_recordsMerges_materializesOnce_setsApprovedDecision_consumes() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.COMPLETED);
        assertThat(result.result())
                .isEqualTo("Tests passed\n\n## Reviewer Feedback\n\nship it\n\n"
                        + "Pull requests merged on approval (squash): acme/app#7.\n\n"
                        + "Roadmap extension approved: created 1 Epics, 0 Stories, 0 Tasks and 0 dependency edges "
                        + "(0 skipped)");
        assertThat(result.attachmentRefs()).isEqualTo(REFS);
        InOrder order = inOrder(merge, runService, gateRecheck);
        order.verify(merge).recordOutcome(eq(runId), any());
        order.verify(runService).materializeAndNote(eq(runId), any());
        order.verify(gateRecheck).consume(approvalId);
        assertThat(stamp.getMaterializedAt()).isEqualTo(NOW);
        assertThat(stamp.getMaterializationNote()).startsWith("Roadmap extension approved: created 1 Epics");
        assertThat(gate.getDecision()).isEqualTo("approved");
        assertThat(gate.getResult()).isEqualTo(result.result());
        assertThat(gate.getArtifactRefs()).isEqualTo(REFS);
    }

    @Test
    void complete_isIdempotent_materializesOnce() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);

        StepResult first = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());
        StepResult second = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());

        assertThat(second).isEqualTo(first);
        verify(runService, times(1)).materializeAndNote(eq(runId), any());
        verify(merge, times(1)).recordOutcome(eq(runId), any());
        verify(gateRecheck, times(1)).consume(approvalId);
    }

    @Test
    void complete_stampAlreadyMaterialized_neverMaterializesAgain_andReusesItsNote() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        stamp.setMaterializedAt(NOW.minusSeconds(5));
        stamp.setMaterializationNote("Roadmap extension approved: created 2 Epics (stored)");

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.COMPLETED);
        assertThat(result.result()).endsWith("\n\nRoadmap extension approved: created 2 Epics (stored)");
        verify(runService, never()).materializeAndNote(any(), any());
        verify(runService, never()).prepareMaterialization(any(), any(), any());
        verify(gateRecheck).consume(approvalId);
    }

    @Test
    void complete_claimedButNeverNoted_completesWithoutARoadmapNote() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        stamp.setMaterializedAt(NOW.minusSeconds(5));

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());

        assertThat(result.result())
                .isEqualTo("Tests passed\n\n## Reviewer Feedback\n\nship it\n\n"
                        + "Pull requests merged on approval (squash): acme/app#7.");
        verify(runService, never()).materializeAndNote(any(), any());
        verify(gateRecheck).consume(approvalId);
    }

    @Test
    void complete_writesTheRoadmapOutsideAnyTransaction_afterCommittingTheClaim() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        int[] open = {0};
        int[] committed = {0};
        when(txManager.getTransaction(any())).thenAnswer(inv -> {
            open[0]++;
            return txStatus;
        });
        doAnswer(inv -> {
                    open[0]--;
                    committed[0]++;
                    return null;
                })
                .when(txManager)
                .commit(any());
        when(runService.materializeAndNote(eq(runId), any())).thenAnswer(inv -> {
            assertThat(open[0]).as("transactions open around the roadmap write").isZero();
            assertThat(committed[0]).as("the claim committed first").isEqualTo(1);
            assertThat(stamp.getMaterializedAt()).isEqualTo(NOW);
            return "Roadmap extension approved: created 1 Epics";
        });

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());

        assertThat(result.result()).endsWith("Roadmap extension approved: created 1 Epics");
        verify(runService).materializeAndNote(eq(runId), any());
        assertThat(open[0]).isZero();
    }

    @Test
    void complete_anOverlappingAttemptClaimedFirst_writesNothing() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        when(runService.prepareMaterialization(runId, RoadmapMaterializeMode.roadmap_extension, doc))
                .thenAnswer(inv -> {
                    stamp.setMaterializedAt(NOW.minusSeconds(1));
                    stamp.setMaterializationNote("written by the other attempt");
                    return RunService.MaterializationPlan.runnable(RoadmapMaterializeMode.roadmap_extension, doc);
                });

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());

        assertThat(result.result()).endsWith("written by the other attempt");
        assertThat(stamp.getMaterializedAt()).isEqualTo(NOW.minusSeconds(1));
        verify(runService, never()).materializeAndNote(any(), any());
    }

    @Test
    void complete_aRoadmapWriteThatStopsPartway_stillCompletes_andSaysSo() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        when(runService.materializeAndNote(eq(runId), any())).thenThrow(new IllegalStateException("connection lost"));

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow(), List.of());

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.COMPLETED);
        assertThat(result.result()).contains("stopped partway").doesNotContain("connection lost");
        assertThat(stamp.getMaterializedAt()).isEqualTo(NOW);
        assertThat(stamp.getMaterializationNote())
                .isEqualTo("Writing the approved roadmap stopped partway, so some items may be missing.");
        verify(gateRecheck).consume(approvalId);
    }

    @Test
    void complete_insideACallersTransaction_isRefused() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.complete(runId, gateExecId, approvalId, mergedRow(), List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(merge, never()).recordOutcome(any(), any());
    }

    @Test
    void complete_duplicateRowResults_areCountedOnce() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        List<PrResultItem> twice = new ArrayList<>(mergedRow());
        twice.add(new PrResultItem(rowId, "MERGED", NOW, "m1"));

        service.complete(runId, gateExecId, approvalId, twice, List.of());

        verify(merge)
                .outcomeOf(
                        eq(runId),
                        any(),
                        eq(List.of(new WorkerPrResult(rowId, Kind.MERGED, NOW, "m1"))),
                        eq(approvalId));
    }

    @Test
    void complete_unknownMergeKind_isBadRequest() {
        stubMergeAndMaterialize(null);

        assertThatThrownBy(() -> service.complete(
                        runId,
                        gateExecId,
                        approvalId,
                        List.of(new PrResultItem(rowId, "SQUASHED", NOW, "m1")),
                        List.of()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SQUASHED");

        verify(merge, never()).recordOutcome(any(), any());
        verify(gateRecheck, never()).consume(any());
    }

    @Test
    void complete_approvalOfAnotherGate_isNotFound() {
        stamp.setGateTemplateNodeId(UUID.randomUUID());

        assertThatThrownBy(() -> service.complete(runId, gateExecId, approvalId, mergedRow(), List.of()))
                .isInstanceOf(NotFoundException.class);

        verify(merge, never()).recordOutcome(any(), any());
    }

    @Test
    void complete_voidedApproval_recordsTheMergesButConflicts() {
        stubMergeAndMaterialize(null);
        stamp.setStatus(GateStampStatus.voided);

        assertThatThrownBy(() -> service.complete(runId, gateExecId, approvalId, mergedRow(), List.of()))
                .isInstanceOf(ConflictException.class);

        verify(merge).recordOutcome(eq(runId), any());
        verify(gateRecheck, never()).consume(any());
        verify(runService, never()).materializeAndNote(any(), any());
    }

    @Test
    void complete_oneStep_unchanged_filesOnTheApi() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);

        StepResult result = service.complete(
                runId,
                gateExecId,
                approvalId,
                mergedRow(),
                List.of(new IssueResultItem(UUID.randomUUID(), 1, "https://github.com/acme/app/issues/1", null)));

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.COMPLETED);
        verify(runService).materializeAndNote(eq(runId), any());
        verify(taskGithubIssueRepo, never()).save(any());
    }

    // ── complete, the split protocol ─────────────────────────────────────────────

    private void consumesOnCall() {
        doAnswer(inv -> {
                    stamp.setStatus(GateStampStatus.consumed);
                    return null;
                })
                .when(gateRecheck)
                .consume(approvalId);
    }

    @Test
    void complete_split_linksFiledIssues_notesUnfiled() throws Exception {
        snapshot(MATERIALIZE_GATE);
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        UUID splitRepoId = UUID.randomUUID();
        stamp.setMaterializationNote("Roadmap extension approved: created 1 Epics, 1 Stories, 2 Tasks and 0 "
                + "dependency edges (0 skipped)");
        stamp.setIssueRequests(objectMapper.writeValueAsString(List.of(
                new IssueRequest(taskA, splitRepoId, "acme/app", "Task A", "body a"),
                new IssueRequest(taskB, splitRepoId, "acme/app", "Task B", "body b"))));
        when(taskRepo.existsById(taskA)).thenReturn(true);
        when(taskRepo.existsById(taskB)).thenReturn(true);
        when(taskGithubIssueRepo.findByTaskId(any())).thenReturn(Optional.empty());
        consumesOnCall();
        List<IssueResultItem> issues = List.of(
                new IssueResultItem(taskA, 10, "https://github.com/acme/app/issues/10", null),
                new IssueResultItem(taskB, null, null, "rate limited"));

        StepResult result = service.complete(runId, gateExecId, approvalId, List.of(), issues);

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.COMPLETED);
        assertThat(result.result())
                .contains("GitHub issues: filed 1 of 2.")
                .contains("Not filed: \"Task B\" (rate limited)");
        ArgumentCaptor<TaskGithubIssue> captor = ArgumentCaptor.forClass(TaskGithubIssue.class);
        verify(taskGithubIssueRepo).save(captor.capture());
        assertThat(captor.getValue().getTaskId()).isEqualTo(taskA);
        assertThat(captor.getValue().getGitRepoId()).isEqualTo(splitRepoId);
        assertThat(captor.getValue().getIssueNumber()).isEqualTo(10);
        verify(runService, never()).prepareMaterialization(any(), any(), any());
    }

    @Test
    void complete_split_heldBackRequest_isNotedTooLarge() throws Exception {
        snapshot(MATERIALIZE_GATE);
        UUID smallTaskId = UUID.randomUUID();
        UUID bigTaskId = UUID.randomUUID();
        UUID splitRepoId = UUID.randomUUID();
        // One request small enough to be handed over, one whose body alone exceeds the budget.
        stamp.setIssueRequests(objectMapper.writeValueAsString(List.of(
                new IssueRequest(smallTaskId, splitRepoId, "acme/app", "Small", "s"),
                new IssueRequest(bigTaskId, splitRepoId, "acme/app", "Big", "x".repeat(300_000)))));
        consumesOnCall();

        StepResult result = service.complete(runId, gateExecId, approvalId, List.of(), List.of());

        assertThat(result.result())
                .contains("\"Big\" (too large to hand to the Worker)")
                .contains("\"Small\" (no result from the Worker)");
        verify(taskGithubIssueRepo, never()).save(any());
    }

    @Test
    void complete_split_ignoresUnrequestedTask_andInvalidUrl() throws Exception {
        snapshot(MATERIALIZE_GATE);
        UUID taskA = UUID.randomUUID();
        UUID splitRepoId = UUID.randomUUID();
        stamp.setIssueRequests(objectMapper.writeValueAsString(
                List.of(new IssueRequest(taskA, splitRepoId, "acme/app", "Task A", "body"))));
        when(taskRepo.existsById(taskA)).thenReturn(true);
        when(taskGithubIssueRepo.findByTaskId(taskA)).thenReturn(Optional.empty());
        consumesOnCall();
        List<IssueResultItem> issues = List.of(
                new IssueResultItem(UUID.randomUUID(), 1, "https://github.com/acme/app/issues/1", null),
                new IssueResultItem(taskA, 1, "ftp://not-http", null));

        StepResult result = service.complete(runId, gateExecId, approvalId, List.of(), issues);

        assertThat(result.result())
                .contains("GitHub issues: filed 0 of 1.")
                .contains("\"Task A\" (the Worker's result could not be linked)");
        verify(taskGithubIssueRepo, never()).save(any());
    }

    @Test
    void complete_split_capsFailureText_andSummarizesUnfiledPastFive() throws Exception {
        snapshot(MATERIALIZE_GATE);
        UUID splitRepoId = UUID.randomUUID();
        List<IssueRequest> requests = new ArrayList<>();
        List<IssueResultItem> issues = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            UUID taskId = UUID.randomUUID();
            requests.add(new IssueRequest(taskId, splitRepoId, "acme/app", "Task " + i, "body"));
            issues.add(new IssueResultItem(taskId, null, null, i == 1 ? "x".repeat(400) : "refused"));
        }
        stamp.setIssueRequests(objectMapper.writeValueAsString(requests));
        consumesOnCall();

        StepResult result = service.complete(runId, gateExecId, approvalId, List.of(), issues);

        assertThat(result.result())
                .contains("GitHub issues: filed 0 of 7.")
                .contains("\"Task 1\" (" + "x".repeat(300) + ")")
                .doesNotContain("x".repeat(301))
                .contains("\"Task 5\" (refused); and 2 more")
                .doesNotContain("Task 6");
        verify(taskGithubIssueRepo, never()).save(any());
    }

    @Test
    void complete_split_repeat_linksOnce() throws Exception {
        snapshot(MATERIALIZE_GATE);
        UUID taskA = UUID.randomUUID();
        UUID splitRepoId = UUID.randomUUID();
        stamp.setIssueRequests(objectMapper.writeValueAsString(
                List.of(new IssueRequest(taskA, splitRepoId, "acme/app", "Task A", "body"))));
        when(taskRepo.existsById(taskA)).thenReturn(true);
        when(taskGithubIssueRepo.findByTaskId(taskA)).thenReturn(Optional.empty());
        consumesOnCall();
        List<IssueResultItem> issues =
                List.of(new IssueResultItem(taskA, 5, "https://github.com/acme/app/issues/5", null));

        StepResult first = service.complete(runId, gateExecId, approvalId, List.of(), issues);
        StepResult second = service.complete(runId, gateExecId, approvalId, List.of(), issues);

        assertThat(second).isEqualTo(first);
        verify(taskGithubIssueRepo, times(1)).save(any());
    }

    @Test
    void complete_split_deletedTask_isSkipped() throws Exception {
        snapshot(MATERIALIZE_GATE);
        UUID taskA = UUID.randomUUID();
        UUID splitRepoId = UUID.randomUUID();
        stamp.setIssueRequests(objectMapper.writeValueAsString(
                List.of(new IssueRequest(taskA, splitRepoId, "acme/app", "Task A", "body"))));
        when(taskRepo.existsById(taskA)).thenReturn(false); // the Task was deleted meanwhile
        consumesOnCall();
        List<IssueResultItem> issues =
                List.of(new IssueResultItem(taskA, 5, "https://github.com/acme/app/issues/5", null));

        StepResult result = service.complete(runId, gateExecId, approvalId, List.of(), issues);

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.COMPLETED);
        assertThat(result.result()).contains("\"Task A\" (the Worker's result could not be linked)");
        verify(taskGithubIssueRepo, never()).save(any());
    }

    // ── refuse ──────────────────────────────────────────────────────────────────

    @Test
    void refuse_voidsApproval_returnsGateToWaiting() {
        when(claims.compareAndSetStatus(gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human))
                .thenReturn(1);

        service.refuse(runId, gateExecId, approvalId, "acme/app#7 has merge conflicts with main", null);

        verify(gateRecheck).voidStamp(approvalId, "acme/app#7 has merge conflicts with main", gateExecId);
        verify(claims)
                .compareAndSetStatus(gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human);
        verify(events).publishNodeStatusChanged(runId, gateExecId, "awaiting_human");
        verify(merge, never()).recordOutcome(any(), any());
    }

    @Test
    void refuse_withPartialMerges_recordsThemBeforeVoiding() {
        snapshot(MERGE_GATE);
        MergePlan plan = mergePlan();
        when(merge.planMerge(runId, PullRequestMergeMethod.squash)).thenReturn(plan);
        MergeOutcome outcome = new MergeOutcome(
                PullRequestMergeMethod.squash,
                List.of(new PullRequestMergeService.Result(prRef(), Kind.MERGED, NOW, "m1")));
        when(merge.outcomeOf(runId, plan, List.of(new WorkerPrResult(rowId, Kind.MERGED, NOW, "m1")), approvalId))
                .thenReturn(outcome);
        when(claims.compareAndSetStatus(any(), any(), any())).thenReturn(1);

        service.refuse(runId, gateExecId, approvalId, "Could not merge acme/app#8: protected branch", mergedRow());

        InOrder order = inOrder(merge, gateRecheck, claims);
        order.verify(merge).recordOutcome(runId, outcome);
        order.verify(gateRecheck).voidStamp(approvalId, "Could not merge acme/app#8: protected branch", gateExecId);
        order.verify(claims)
                .compareAndSetStatus(gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human);
    }

    @Test
    void refuse_retryAfterRefusing_changesNothing() {
        stamp.setStatus(GateStampStatus.voided);
        stamp.setVoidReason("refused before");

        service.refuse(runId, gateExecId, approvalId, "refused before", null);

        verify(gateRecheck, never()).voidStamp(any(), any(), any());
        verify(claims, never()).compareAndSetStatus(any(), any(), any());
        verify(events, never()).publishNodeStatusChanged(any(), any(), any());
    }

    @Test
    void refuse_consumedApproval_conflicts() {
        stamp.setStatus(GateStampStatus.consumed);

        assertThatThrownBy(() -> service.refuse(runId, gateExecId, approvalId, "late", null))
                .isInstanceOf(ConflictException.class);

        verify(claims, never()).compareAndSetStatus(any(), any(), any());
    }

    @Test
    void refuse_blankReason_stillNamesWhy() {
        when(claims.compareAndSetStatus(any(), any(), any())).thenReturn(1);

        service.refuse(runId, gateExecId, approvalId, " ", null);

        verify(gateRecheck).voidStamp(eq(approvalId), contains("Finalizing the approval failed"), eq(gateExecId));
    }
}
