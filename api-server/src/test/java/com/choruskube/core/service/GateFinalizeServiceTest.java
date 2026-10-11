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
import com.choruskube.core.dto.GateFinalizeDtos.PlanResponse;
import com.choruskube.core.dto.GateFinalizeDtos.PrItem;
import com.choruskube.core.dto.GateFinalizeDtos.PrResultItem;
import com.choruskube.core.dto.GateFinalizeDtos.StepResult;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.dto.SignalResponse;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.GateClaimConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.DriftRecheckConfig;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
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

        verify(gateRecheck).voidStamp(eq(approvalId), contains("Could not start finalizing"), eq(gateExecId));
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
    void plan_gateOnTheSynchronousPath_isNotFound() {
        gate.setFinalizeMode(null);

        assertThatThrownBy(() -> service.plan(runId, gateExecId, approvalId)).isInstanceOf(NotFoundException.class);
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

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow());

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
        assertThat(gate.getDecision()).isEqualTo("approved");
        assertThat(gate.getResult()).isEqualTo(result.result());
        assertThat(gate.getArtifactRefs()).isEqualTo(REFS);
    }

    @Test
    void complete_isIdempotent_materializesOnce() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);

        StepResult first = service.complete(runId, gateExecId, approvalId, mergedRow());
        StepResult second = service.complete(runId, gateExecId, approvalId, mergedRow());

        assertThat(second).isEqualTo(first);
        verify(runService, times(1)).materializeAndNote(eq(runId), any());
        verify(merge, times(1)).recordOutcome(eq(runId), any());
        verify(gateRecheck, times(1)).consume(approvalId);
    }

    @Test
    void complete_stampAlreadyMaterialized_neverMaterializesAgain() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        stamp.setMaterializedAt(NOW.minusSeconds(5));

        StepResult result = service.complete(runId, gateExecId, approvalId, mergedRow());

        assertThat(result.outcome()).isEqualTo(GateFinalizeService.COMPLETED);
        verify(runService, never()).materializeAndNote(any(), any());
        verify(gateRecheck).consume(approvalId);
    }

    @Test
    void complete_aFailedRoadmapWrite_failsTheStepWithoutConsuming() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        when(runService.materializeAndNote(eq(runId), any())).thenAnswer(inv -> {
            txStatus.setRollbackOnly();
            return "Roadmap extension approved: created 0 Epics\nSkipped: Failed to materialize candidate Epic 'x'";
        });

        assertThatThrownBy(() -> service.complete(runId, gateExecId, approvalId, mergedRow()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to materialize candidate Epic 'x'");

        verify(gateRecheck, never()).consume(any());
        verify(merge).recordOutcome(eq(runId), any());
    }

    @Test
    void complete_duplicateRowResults_areCountedOnce() {
        RoadmapCandidatesDocument doc = new RoadmapCandidatesDocument(null, List.of(), null);
        stubMergeAndMaterialize(doc);
        List<PrResultItem> twice = new ArrayList<>(mergedRow());
        twice.add(new PrResultItem(rowId, "MERGED", NOW, "m1"));

        service.complete(runId, gateExecId, approvalId, twice);

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
                        runId, gateExecId, approvalId, List.of(new PrResultItem(rowId, "SQUASHED", NOW, "m1"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SQUASHED");

        verify(merge, never()).recordOutcome(any(), any());
        verify(gateRecheck, never()).consume(any());
    }

    @Test
    void complete_approvalOfAnotherGate_isNotFound() {
        stamp.setGateTemplateNodeId(UUID.randomUUID());

        assertThatThrownBy(() -> service.complete(runId, gateExecId, approvalId, mergedRow()))
                .isInstanceOf(NotFoundException.class);

        verify(merge, never()).recordOutcome(any(), any());
    }

    @Test
    void complete_voidedApproval_recordsTheMergesButConflicts() {
        stubMergeAndMaterialize(null);
        stamp.setStatus(GateStampStatus.voided);

        assertThatThrownBy(() -> service.complete(runId, gateExecId, approvalId, mergedRow()))
                .isInstanceOf(ConflictException.class);

        verify(merge).recordOutcome(eq(runId), any());
        verify(gateRecheck, never()).consume(any());
        verify(runService, never()).materializeAndNote(any(), any());
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
