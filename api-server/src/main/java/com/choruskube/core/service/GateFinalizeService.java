package com.choruskube.core.service;

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
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.GateDriftCheckService.CompareResult;
import com.choruskube.core.service.GateDriftCheckService.DriftCheck;
import com.choruskube.core.service.GateDriftCheckService.DriftInputs;
import com.choruskube.core.service.PullRequestMergeService.Kind;
import com.choruskube.core.service.PullRequestMergeService.MergeOutcome;
import com.choruskube.core.service.PullRequestMergeService.MergePlan;
import com.choruskube.core.service.PullRequestMergeService.WorkerPrResult;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Finishes an approval on a gate whose workflow drives it in steps ({@code finalize_mode = 'async'}).
 * The click claims the gate and records the approval as a {@code finalizing} {@link GateStamp}; the
 * workflow then calls plan, then either complete directly (the one-step protocol) or materialize
 * followed by complete (the split protocol, once the approval's roadmap write and GitHub issue
 * filing moved to a separate step and the run's Worker respectively) — or hold or refuse instead. A
 * Temporal retry may repeat any step after it already succeeded, so each one answers a repeat with
 * the result it gave the first time and changes nothing twice.
 */
@Service
public class GateFinalizeService {

    public static final String PROCEED = "proceed";
    public static final String HOLD = "hold";
    public static final String REFUSED = "refused";
    public static final String COMPLETED = "completed";
    public static final String HELD = "held";

    static final String GUIDANCE_FILE = "human_guidance.md";

    // Shown on the gate, so they carry no exception text: that can name internal hosts.
    static final String START_FAILED = "Could not start finalizing the approval. Approve again to retry.";
    static final String MATERIALIZATION_STOPPED =
            "Writing the approved roadmap stopped partway, so some items may be missing.";

    /**
     * The largest serialized size of the issue requests a materialize response hands to the
     * orchestrator. Temporal rejects a workflow-history payload over its 2 MB blob limit; staying
     * well under it tolerates the JSON escaping a Go unmarshal/marshal round trip can add.
     */
    static final int ISSUE_HANDOVER_BUDGET_BYTES = 256 * 1024;

    /** GitHub's issue body limit; a longer description is cut before it is stored for the Worker. */
    private static final int ISSUE_BODY_MAX_CHARS = 65_000;

    private static final int ISSUE_FAILURE_MAX_CHARS = 300;
    private static final int ISSUE_NOTE_MAX_UNFILED_SHOWN = 5;

    private static final Logger log = LoggerFactory.getLogger(GateFinalizeService.class);

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository execRepo;
    private final GateStampRepository stampRepo;
    private final TaskRepository taskRepo;
    private final TaskGithubIssueRepository taskGithubIssueRepo;
    private final RunService runService;
    private final GateRecheckService gateRecheck;
    private final GateDriftCheckService drift;
    private final PullRequestMergeService merge;
    private final NodeExecutionClaimService claims;
    private final UploadService uploads;
    private final StoragePrefixResolver storagePrefixes;
    private final DecisionOptionsResolver decisionOptions;
    private final RunEventPublisher events;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public GateFinalizeService(
            WorkflowRunRepository runRepo,
            NodeExecutionRepository execRepo,
            GateStampRepository stampRepo,
            TaskRepository taskRepo,
            TaskGithubIssueRepository taskGithubIssueRepo,
            RunService runService,
            GateRecheckService gateRecheck,
            GateDriftCheckService drift,
            PullRequestMergeService merge,
            NodeExecutionClaimService claims,
            UploadService uploads,
            StoragePrefixResolver storagePrefixes,
            DecisionOptionsResolver decisionOptions,
            RunEventPublisher events,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.runRepo = runRepo;
        this.execRepo = execRepo;
        this.stampRepo = stampRepo;
        this.taskRepo = taskRepo;
        this.taskGithubIssueRepo = taskGithubIssueRepo;
        this.runService = runService;
        this.gateRecheck = gateRecheck;
        this.drift = drift;
        this.merge = merge;
        this.claims = claims;
        this.uploads = uploads;
        this.storagePrefixes = storagePrefixes;
        this.decisionOptions = decisionOptions;
        this.events = events;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** The approve click on an async gate: claim, record, hand to the workflow, reply. */
    public SignalResponse requestApproval(
            WorkflowRun run,
            NodeExecution gate,
            SignalRequest request,
            JsonNode snapshot,
            DriftRecheckConfig recheckOrNull,
            RoadmapCandidatesDocument approvedCandidates) {
        UUID runId = run.getId();
        String label = RunService.nodeLabel(snapshot, gate.getTemplateNodeId());
        StampMode mode = recheckOrNull == null ? StampMode.always : recheckOrNull.stamp();
        GateStamp stamp = tx.execute(status -> {
            if (claims.compareAndSetStatus(
                            gate.getId(), NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing)
                    == 0) {
                throw new GateClaimConflictException(
                        "Cannot approve: the gate is not awaiting a decision (already deciding or decided)");
            }
            gateRecheck.voidPendingFor(runId, gate.getTemplateNodeId(), GateRecheckService.REVIEWER_DECIDED);
            gateRecheck.voidLeftBehind(runId, gate.getTemplateNodeId(), gate.getId());
            // Hibernate flushes inserts before updates, and only one live approval per gate may exist.
            stampRepo.flush();
            return gateRecheck.begin(
                    runId, gate, label, mode, request.feedback(), request.attachmentRefs(), approvedCandidates);
        });
        try {
            runService.signalGate(
                    run, gate.getId(), "approved", request.feedback(), request.attachmentRefs(), stamp.getId());
        } catch (RuntimeException e) {
            log.error("Could not signal the approval of gate {} of run {}: {}", gate.getId(), runId, e.getMessage(), e);
            release(runId, gate.getId(), stamp.getId(), START_FAILED);
            throw e;
        }
        publish(runId, gate.getId(), NodeExecutionStatus.finalizing);
        return SignalResponse.finalizing();
    }

    /**
     * Asked by the workflow as a gate opens: re-apply a held approval if one still applies. Losing
     * the gate to a reviewer's click returns none, and the workflow then waits for their signal.
     */
    public HeldClaim claimHeld(UUID runId, UUID gateExecId) {
        NodeExecution gate = requireGate(runId, gateExecId);
        Optional<GateStamp> finalizing = gateRecheck.findFinalizingFor(runId, gate.getTemplateNodeId());
        if (finalizing.isPresent()) {
            // A reviewer's approval is stamped at this execution and reaches the workflow by signal;
            // one stamped elsewhere is the held approval this step already claimed.
            GateStamp live = finalizing.get();
            boolean claimedHere = gate.getStatus() == NodeExecutionStatus.finalizing
                    && !gateExecId.equals(live.getStampedNodeExecutionId());
            return new HeldClaim(claimedHere ? live.getId() : null);
        }
        Optional<GateStamp> held = gateRecheck.findHeldFor(runId, gate.getTemplateNodeId());
        if (held.isEmpty()) {
            return new HeldClaim(null);
        }
        Optional<String> reason = gateRecheck.reasonNotToReapply(held.get(), runService.readSnapshot(run(runId)));
        if (reason.isPresent()) {
            gateRecheck.voidStamp(held.get().getId(), reason.get(), gateExecId);
            publish(runId, gateExecId, NodeExecutionStatus.awaiting_human);
            return new HeldClaim(null);
        }
        UUID claimed = tx.execute(status -> {
            if (claims.compareAndSetStatus(
                            gateExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.finalizing)
                    == 0) {
                return null;
            }
            Optional<GateStamp> stamp = gateRecheck.claimHeld(runId, gate.getTemplateNodeId());
            if (stamp.isEmpty()) {
                status.setRollbackOnly();
                return null;
            }
            return stamp.get().getId();
        });
        if (claimed != null) {
            publish(runId, gateExecId, NodeExecutionStatus.finalizing);
        }
        return new HeldClaim(claimed);
    }

    /** What the Worker must check and merge, or why the approval holds or is refused without it. */
    public PlanResponse plan(UUID runId, UUID gateExecId, UUID approvalId) {
        NodeExecution gate = requireGate(runId, gateExecId);
        GateStamp stamp = approval(runId, gate, approvalId);
        if (stamp.getStatus() == GateStampStatus.voided) {
            return refusedPlan(stamp.getVoidReason());
        }
        requireFinalizing(gate, stamp);
        JsonNode snapshot = runService.readSnapshot(run(runId));
        JsonNode config = nodeConfig(snapshot, gate);

        Optional<RoadmapMaterializeMode> mode = RoadmapMaterializeMode.fromConfigOverrides(config);
        RunService.MaterializationPlan materializationPlan = null;
        if (mode.isPresent()) {
            try {
                materializationPlan =
                        runService.prepareMaterialization(runId, mode.get(), gateRecheck.approvedCandidates(stamp));
            } catch (ValidationException e) {
                return refusedPlan("The approved roadmap proposal is invalid: " + String.join("; ", e.getErrors()));
            }
        }

        List<CompareItem> compares = List.of();
        if (DriftRecheckConfig.fromConfigOverrides(config).isPresent()) {
            DriftInputs inputs = drift.gather(runId, gate.getTemplateNodeId(), snapshot);
            if (!inputs.known().isEmpty()) {
                return new PlanResponse(HOLD, null, List.of(), List.of(), null, false);
            }
            compares = inputs.toCompare().stream()
                    .map(c -> new CompareItem(c.gitRepoId(), c.ownerRepo(), c.branch(), c.baseSha()))
                    .toList();
        }

        List<PrItem> prs = List.of();
        String mergeMethod = null;
        Optional<PullRequestMergeMethod> method = PullRequestMergeMethod.fromConfigOverrides(config);
        if (method.isPresent()) {
            MergePlan mergePlan = merge.planMerge(runId, method.get());
            if (!mergePlan.blockers().isEmpty()) {
                return refusedPlan("Approval needs every pull request to be mergeable; nothing was merged. "
                        + String.join("; ", mergePlan.blockers()) + ".");
            }
            prs = mergePlan.toInspect().stream()
                    .map(p -> new PrItem(p.rowId(), p.ownerRepo(), p.number(), p.label(), p.prUrl()))
                    .toList();
            mergeMethod = method.get().name();
        }
        // The capability signal the orchestrator uses to pick the split write-then-file protocol
        // over the one-step complete; an older API's response simply omits the field.
        boolean writesRoadmap = mode.isPresent() && materializationPlan.runnable();
        return new PlanResponse(PROCEED, null, compares, prs, mergeMethod, writesRoadmap);
    }

    /**
     * Holds the approval for a re-check: writes the guidance, returns the approval to {@code
     * pending} one round further on, and records the gate's route decision. At the round cap, or
     * when a re-check could not help, the approval is refused instead.
     */
    public StepResult hold(UUID runId, UUID gateExecId, UUID approvalId, List<CompareResultItem> compares) {
        NodeExecution gate = requireGate(runId, gateExecId);
        GateStamp stamp = approval(runId, gate, approvalId);
        if (stamp.getStatus() == GateStampStatus.voided) {
            return refusedStep(stamp.getVoidReason());
        }
        if (stamp.getStatus() == GateStampStatus.pending
                && gate.getStatus() == NodeExecutionStatus.finalizing
                && gate.getDecision() != null) {
            return new StepResult(HELD, gate.getResult(), gate.getArtifactRefs(), null);
        }
        requireFinalizing(gate, stamp);
        JsonNode snapshot = runService.readSnapshot(run(runId));
        DriftRecheckConfig recheck = DriftRecheckConfig.fromConfigOverrides(nodeConfig(snapshot, gate))
                .orElseThrow(() -> new ConflictException("Gate " + gateExecId + " declares no drift re-check"));
        DriftCheck check =
                drift.classify(drift.gather(runId, gate.getTemplateNodeId(), snapshot), toCompareResults(compares));

        if (stamp.getRecheckRounds() > 0 && RunService.cannotBeFixedByARecheck(check)) {
            return refuseFromHold(
                    runId,
                    gateExecId,
                    approvalId,
                    "The re-check could not tell which main commit was tested (" + check.summary()
                            + "). The project's agent image may predate base recording, so rebuild it on the "
                            + "current claude-code image.");
        }
        if (stamp.getRecheckRounds() >= GateDriftCheckService.MAX_RECHECK_ROUNDS) {
            return refuseFromHold(
                    runId,
                    gateExecId,
                    approvalId,
                    "The default branch moved again after " + GateDriftCheckService.MAX_RECHECK_ROUNDS
                            + " re-check rounds: " + check.summary() + ". Approve again to start a new re-check.");
        }

        int round = stamp.getRecheckRounds() + 1;
        String route = runService.validateDecisionAgainstEdges(snapshot, gate.getTemplateNodeId(), recheck.route());
        String guidanceRefs = uploads.uploadGateText(
                storagePrefixes.storagePrefixForRun(runId),
                runId,
                gateExecId,
                GUIDANCE_FILE,
                drift.guidance(runId, check, round));
        String result = RunService.appendNote(
                gate.getResult(),
                "Approval held for a re-check against current main (round " + round + " of "
                        + GateDriftCheckService.MAX_RECHECK_ROUNDS + "): " + check.summary() + ".");
        GateStamp held = tx.execute(status -> {
            GateStamp locked = lock(approvalId);
            if (locked.getStatus() != GateStampStatus.finalizing) {
                throw new ConflictException(
                        "Approval " + approvalId + " is " + locked.getStatus() + ", not finalizing");
            }
            GateStamp h = gateRecheck.holdFromFinalizing(approvalId);
            // Stored on the gate so a repeated hold answers with exactly what the first one returned.
            recordOnGate(gateExecId, route, result, guidanceRefs);
            return h;
        });
        auditHeld(runId, held, check);
        return new StepResult(HELD, result, guidanceRefs, null);
    }

    /**
     * The write step of the split protocol: writes the approved roadmap at most once and hands
     * back the GitHub issues it wants filed for the run's Worker, never calling GitHub itself. A
     * repeat returns the stored requests rather than writing again.
     */
    public MaterializeResponse materialize(UUID runId, UUID gateExecId, UUID approvalId, List<PrResultItem> merges) {
        // Each step commits on its own, the same reason complete does: inside a caller's
        // transaction the claim would not commit before the roadmap write runs.
        Assert.state(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "Materializing a gate approval must run outside a transaction");
        NodeExecution gate = requireGate(runId, gateExecId);
        GateStamp stamp = approval(runId, gate, approvalId);
        List<WorkerPrResult> results = toWorkerResults(merges);
        if (stamp.getStatus() == GateStampStatus.consumed) {
            return storedMaterializeResponse(approvalId);
        }
        JsonNode snapshot = runService.readSnapshot(run(runId));
        JsonNode config = nodeConfig(snapshot, gate);
        // Merged on GitHub is merged whatever happens to the approval, so it is recorded first.
        recordMerges(runId, config, results, approvalId);
        requireFinalizing(gate, stamp);

        if (stamp.getIssueRequests() != null) {
            return storedMaterializeResponse(approvalId);
        }

        Optional<RoadmapMaterializeMode> mode = RoadmapMaterializeMode.fromConfigOverrides(config);
        if (mode.isEmpty()) {
            return storeIssueRequestsOnce(approvalId, null, List.of());
        }
        if (stamp.getMaterializedAt() != null) {
            // Claimed by an earlier attempt that never stored anything: a crash after the claim,
            // or this attempt losing a race to one still running. Either way, writing again would
            // duplicate the roadmap, so this is the same "stopped partway" outcome as a crash.
            return storeIssueRequestsOnce(approvalId, MATERIALIZATION_STOPPED, List.of());
        }

        RunService.MaterializationPlan plan =
                runService.prepareMaterialization(runId, mode.get(), gateRecheck.approvedCandidates(stamp));
        if (!plan.runnable()) {
            return storeIssueRequestsOnce(approvalId, plan.fixedNote(), List.of());
        }
        if (!Boolean.TRUE.equals(tx.execute(s -> claimMaterialization(approvalId)))) {
            return storeIssueRequestsOnce(approvalId, MATERIALIZATION_STOPPED, List.of());
        }

        String note;
        List<TaskIssueTarget> targets;
        try {
            RunService.MaterializationOutcome outcome = runService.materializeForWorker(runId, plan);
            note = outcome.note();
            targets = outcome.issueTargets();
        } catch (RuntimeException e) {
            // Already claimed, so no retry writes it again; the note is the reviewer's only trace.
            log.error("Writing the approved roadmap of run {} stopped partway: {}", runId, e.getMessage(), e);
            note = MATERIALIZATION_STOPPED;
            targets = List.of();
        }
        List<IssueRequest> requests = targets.stream()
                .map(t -> new IssueRequest(
                        t.taskId(), t.gitRepoId(), t.ownerRepo(), t.title(), cutToGithubBodyLimit(t.body())))
                .toList();
        return storeIssueRequestsOnce(approvalId, note, requests);
    }

    /** {@code requests} is cut to {@link #ISSUE_HANDOVER_BUDGET_BYTES} so a repeat always answers from storage. */
    private MaterializeResponse storeIssueRequestsOnce(UUID approvalId, String note, List<IssueRequest> requests) {
        tx.executeWithoutResult(status -> {
            GateStamp locked = lock(approvalId);
            // Only the first attempt to reach here writes: a losing attempt (a crash recovered by
            // a retry, or two overlapping calls) must answer with the winner's requests, not its own.
            if (locked.getIssueRequests() == null) {
                locked.setMaterializationNote(note);
                locked.setIssueRequests(writeIssueRequests(requests));
                stampRepo.save(locked);
            }
        });
        return storedMaterializeResponse(approvalId);
    }

    private MaterializeResponse storedMaterializeResponse(UUID approvalId) {
        GateStamp fresh = stampRepo
                .findById(approvalId)
                .orElseThrow(() -> new NotFoundException("Approval not found: " + approvalId));
        return new MaterializeResponse(
                fresh.getMaterializedAt(), handedOver(parseIssueRequests(fresh.getIssueRequests())));
    }

    /**
     * The longest leading run of {@code requests} whose serialized size stays within the hand-over
     * budget: the orchestrator carries this response through workflow history, which Temporal
     * rejects outright once it crosses its own, much larger blob limit. Package-private so {@link
     * #complete} reuses it to tell a held-back request from one the Worker simply never answered.
     */
    List<IssueRequest> handedOver(List<IssueRequest> requests) {
        List<IssueRequest> kept = new ArrayList<>();
        long total = 0;
        for (IssueRequest request : requests) {
            int size;
            try {
                size = objectMapper.writeValueAsBytes(request).length;
            } catch (Exception e) {
                throw new IllegalStateException("Could not serialize an issue request", e);
            }
            if (total + size > ISSUE_HANDOVER_BUDGET_BYTES) {
                break;
            }
            total += size;
            kept.add(request);
        }
        return kept;
    }

    private String writeIssueRequests(List<IssueRequest> requests) {
        try {
            return objectMapper.writeValueAsString(requests);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize this approval's issue requests", e);
        }
    }

    private List<IssueRequest> parseIssueRequests(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<IssueRequest>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Approval has unreadable issue requests", e);
        }
    }

    private static String cutToGithubBodyLimit(String body) {
        return body != null && body.length() > ISSUE_BODY_MAX_CHARS ? body.substring(0, ISSUE_BODY_MAX_CHARS) : body;
    }

    /**
     * Records the Worker's merges, writes the approved roadmap at most once (the one-step
     * protocol) or links the issues the run's Worker filed for an already-written one (the split
     * protocol, told apart by {@code issue_requests} being non-null), then records the gate's
     * {@code approved} decision and result. A repeat after success returns the stored result.
     */
    public StepResult complete(
            UUID runId, UUID gateExecId, UUID approvalId, List<PrResultItem> merges, List<IssueResultItem> issues) {
        // Each step commits on its own: inside a caller's transaction the claim would not commit
        // before the roadmap write, and a rejected write would roll the whole approval back.
        Assert.state(
                !TransactionSynchronizationManager.isActualTransactionActive(),
                "Completing a gate approval must run outside a transaction");
        NodeExecution gate = requireGate(runId, gateExecId);
        GateStamp stamp = approval(runId, gate, approvalId);
        List<WorkerPrResult> results = toWorkerResults(merges);
        if (stamp.getStatus() == GateStampStatus.consumed) {
            return completedReplay(gate, stamp);
        }
        JsonNode snapshot = runService.readSnapshot(run(runId));
        JsonNode config = nodeConfig(snapshot, gate);
        // Merged on GitHub is merged whatever happens to the approval, so it is recorded first.
        MergeOutcome outcome = recordMerges(runId, config, results, approvalId);
        requireFinalizing(gate, stamp);
        String mergeNote = outcome == null ? null : merge.note(outcome);

        if (stamp.getIssueRequests() != null) {
            return completeSplit(runId, gateExecId, gate, stamp, approvalId, snapshot, mergeNote, issues);
        }
        if (!issues.isEmpty()) {
            log.warn("Run {}: ignoring {} issue results on the one-step completion path", runId, issues.size());
        }

        Optional<RoadmapMaterializeMode> mode = RoadmapMaterializeMode.fromConfigOverrides(config);
        RunService.MaterializationPlan plan = mode.isPresent() && stamp.getMaterializedAt() == null
                ? runService.prepareMaterialization(runId, mode.get(), gateRecheck.approvedCandidates(stamp))
                : null;
        String approved = runService.validateDecisionAgainstEdges(snapshot, gate.getTemplateNodeId(), "approved");

        if (plan != null && plan.runnable() && Boolean.TRUE.equals(tx.execute(s -> claimMaterialization(approvalId)))) {
            String note = materializeOneStep(runId, plan);
            tx.executeWithoutResult(s -> {
                GateStamp locked = lock(approvalId);
                locked.setMaterializationNote(note);
                stampRepo.save(locked);
            });
        }

        return tx.execute(status -> {
            GateStamp locked = lock(approvalId);
            if (locked.getStatus() == GateStampStatus.consumed) {
                return completedReplay(execRepo.findById(gateExecId).orElseThrow(), locked);
            }
            if (locked.getStatus() != GateStampStatus.finalizing) {
                throw new ConflictException(
                        "Approval " + approvalId + " is " + locked.getStatus() + ", not finalizing");
            }
            String roadmapNote = plan != null && !plan.runnable() ? plan.fixedNote() : locked.getMaterializationNote();
            String result = RunService.appendNote(
                    RunService.appendNote(withFeedback(gate.getResult(), locked.getFeedback()), mergeNote),
                    roadmapNote);
            String attachments = attachmentsOf(locked);
            recordOnGate(gateExecId, approved, result, attachments);
            gateRecheck.consume(approvalId);
            return new StepResult(COMPLETED, result, attachments, null);
        });
    }

    /**
     * The split protocol's completion: the roadmap is already written, so this only links each
     * filed issue to its task and notes any that were not filed, inside the same transaction that
     * records the decision and consumes the approval — so a repeat links nothing twice.
     */
    private StepResult completeSplit(
            UUID runId,
            UUID gateExecId,
            NodeExecution gate,
            GateStamp stamp,
            UUID approvalId,
            JsonNode snapshot,
            String mergeNote,
            List<IssueResultItem> issues) {
        List<IssueRequest> requested = parseIssueRequests(stamp.getIssueRequests());
        Set<UUID> requestedTaskIds =
                requested.stream().map(IssueRequest::taskId).collect(Collectors.toSet());
        Set<UUID> handedOverTaskIds =
                handedOver(requested).stream().map(IssueRequest::taskId).collect(Collectors.toSet());
        // First result wins, and a task this approval never requested is ignored outright: a
        // Worker can be run by a customer, so its results are untrusted input.
        Map<UUID, IssueResultItem> resultByTask = new LinkedHashMap<>();
        for (IssueResultItem item : issues) {
            if (item != null && item.taskId() != null && requestedTaskIds.contains(item.taskId())) {
                resultByTask.putIfAbsent(item.taskId(), item);
            }
        }
        String approved = runService.validateDecisionAgainstEdges(snapshot, gate.getTemplateNodeId(), "approved");

        return tx.execute(status -> {
            GateStamp locked = lock(approvalId);
            if (locked.getStatus() == GateStampStatus.consumed) {
                return completedReplay(execRepo.findById(gateExecId).orElseThrow(), locked);
            }
            if (locked.getStatus() != GateStampStatus.finalizing) {
                throw new ConflictException(
                        "Approval " + approvalId + " is " + locked.getStatus() + ", not finalizing");
            }
            int filed = 0;
            List<String> unfiled = new ArrayList<>();
            for (IssueRequest request : requested) {
                IssueResultItem result = resultByTask.get(request.taskId());
                if (isLinkable(request, result)) {
                    TaskGithubIssue linkage = new TaskGithubIssue();
                    linkage.setTaskId(request.taskId());
                    linkage.setGitRepoId(request.gitRepoId());
                    linkage.setIssueNumber(result.number());
                    linkage.setIssueUrl(result.url());
                    linkage.setState(GithubIssueState.open);
                    taskGithubIssueRepo.save(linkage);
                    filed++;
                } else {
                    unfiled.add(
                            "\"" + request.title() + "\" (" + unfiledReason(request, result, handedOverTaskIds) + ")");
                }
            }
            String issueNote = requested.isEmpty() ? null : issueNote(filed, requested.size(), unfiled);
            String roadmapNote = locked.getMaterializationNote();
            String result = RunService.appendNote(
                    RunService.appendNote(
                            RunService.appendNote(withFeedback(gate.getResult(), locked.getFeedback()), mergeNote),
                            roadmapNote),
                    issueNote);
            String attachments = attachmentsOf(locked);
            recordOnGate(gateExecId, approved, result, attachments);
            gateRecheck.consume(approvalId);
            return new StepResult(COMPLETED, result, attachments, null);
        });
    }

    /**
     * A result is only linked when it carries a positive issue number and an http(s) URL, names a
     * Task that still exists (one deleted meanwhile is skipped rather than failing the transaction),
     * and that Task has no linkage already — a Worker can be run by a customer, so none of this is
     * trusted without checking.
     */
    private boolean isLinkable(IssueRequest request, IssueResultItem result) {
        return result != null
                && result.number() != null
                && result.number() > 0
                && result.url() != null
                && (result.url().startsWith("https://") || result.url().startsWith("http://"))
                && taskRepo.existsById(request.taskId())
                && taskGithubIssueRepo.findByTaskId(request.taskId()).isEmpty();
    }

    private static String unfiledReason(IssueRequest request, IssueResultItem result, Set<UUID> handedOverTaskIds) {
        if (result != null && result.failure() != null) {
            return capAt(result.failure(), ISSUE_FAILURE_MAX_CHARS);
        }
        return handedOverTaskIds.contains(request.taskId())
                ? "no result from the Worker"
                : "too large to hand to the Worker";
    }

    private static String issueNote(int filed, int total, List<String> unfiled) {
        StringBuilder sb = new StringBuilder("GitHub issues: filed ")
                .append(filed)
                .append(" of ")
                .append(total)
                .append('.');
        if (!unfiled.isEmpty()) {
            int shown = Math.min(ISSUE_NOTE_MAX_UNFILED_SHOWN, unfiled.size());
            sb.append(" Not filed: ").append(String.join("; ", unfiled.subList(0, shown)));
            if (unfiled.size() > shown) {
                sb.append("; and ").append(unfiled.size() - shown).append(" more");
            }
        }
        return sb.toString();
    }

    private static String capAt(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * Records any merges the Worker made, then voids the approval with {@code reason} shown on the
     * gate and returns the gate to {@code awaiting_human}. A repeat after success changes nothing.
     */
    public void refuse(UUID runId, UUID gateExecId, UUID approvalId, String reason, List<PrResultItem> merges) {
        NodeExecution gate = requireGate(runId, gateExecId);
        GateStamp stamp = approval(runId, gate, approvalId);
        List<WorkerPrResult> results = toWorkerResults(merges);
        if (!results.isEmpty()) {
            recordMerges(runId, nodeConfig(runService.readSnapshot(run(runId)), gate), results, approvalId);
        }
        if (stamp.getStatus() == GateStampStatus.voided) {
            return;
        }
        if (stamp.getStatus() != GateStampStatus.finalizing) {
            throw new ConflictException(
                    "Approval " + approvalId + " is " + stamp.getStatus() + ", so it cannot be refused");
        }
        String text = reason == null || reason.isBlank() ? "Finalizing the approval failed." : reason;
        Boolean reopened = tx.execute(status -> {
            if (lock(approvalId).getStatus() != GateStampStatus.finalizing) {
                return false;
            }
            gateRecheck.voidStamp(approvalId, text, gateExecId);
            return claims.compareAndSetStatus(
                            gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human)
                    == 1;
        });
        if (Boolean.TRUE.equals(reopened)) {
            publish(runId, gateExecId, NodeExecutionStatus.awaiting_human);
        }
    }

    /** The gate execution behind an internal step: in this run, and opened on the async protocol. */
    public NodeExecution requireGate(UUID runId, UUID gateExecId) {
        NodeExecution gate = execRepo.findById(gateExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + gateExecId));
        NodeExecutionUtil.requireInRun(gate, runId);
        if (!RunService.ASYNC_FINALIZE.equals(gate.getFinalizeMode())) {
            throw new NotFoundException("Node execution not found: " + gateExecId);
        }
        return gate;
    }

    private GateStamp approval(UUID runId, NodeExecution gate, UUID approvalId) {
        GateStamp stamp = stampRepo
                .findById(approvalId)
                .orElseThrow(() -> new NotFoundException("Approval not found: " + approvalId));
        if (!runId.equals(stamp.getWorkflowRunId())
                || !gate.getTemplateNodeId().equals(stamp.getGateTemplateNodeId())) {
            throw new NotFoundException("Approval not found: " + approvalId);
        }
        return stamp;
    }

    private static void requireFinalizing(NodeExecution gate, GateStamp stamp) {
        if (stamp.getStatus() != GateStampStatus.finalizing || gate.getStatus() != NodeExecutionStatus.finalizing) {
            throw new ConflictException("Approval " + stamp.getId() + " is " + stamp.getStatus() + " and gate "
                    + gate.getId() + " is " + gate.getStatus() + "; neither can be finalized");
        }
    }

    /**
     * Commits {@code materialized_at} before anything is written: a retry, or an attempt
     * overlapping this one, then skips the write instead of creating the roadmap twice.
     */
    private boolean claimMaterialization(UUID approvalId) {
        GateStamp locked = lock(approvalId);
        if (locked.getStatus() != GateStampStatus.finalizing || locked.getMaterializedAt() != null) {
            return false;
        }
        locked.setMaterializedAt(clock.instant());
        stampRepo.save(locked);
        return true;
    }

    /**
     * The one-step protocol's roadmap write, which still files GitHub issues on the API. Runs with
     * no surrounding transaction, so each write commits on its own and a write the write path
     * rejects is skipped and noted rather than rolling back the rest.
     */
    private String materializeOneStep(UUID runId, RunService.MaterializationPlan plan) {
        try {
            return runService.materializeAndNote(runId, plan);
        } catch (RuntimeException e) {
            // Already claimed, so no retry writes it again; the note is the reviewer's only trace.
            log.error("Writing the approved roadmap of run {} stopped partway: {}", runId, e.getMessage(), e);
            return MATERIALIZATION_STOPPED;
        }
    }

    private GateStamp lock(UUID approvalId) {
        return stampRepo
                .findLockedById(approvalId)
                .orElseThrow(() -> new NotFoundException("Approval not found: " + approvalId));
    }

    private WorkflowRun run(UUID runId) {
        return runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
    }

    private JsonNode nodeConfig(JsonNode snapshot, NodeExecution gate) {
        return decisionOptions.findNodeConfigOverrides(snapshot.get("nodes"), gate.getTemplateNodeId());
    }

    private void recordOnGate(UUID gateExecId, String decision, String result, String artifactRefs) {
        NodeExecution gate = execRepo.findById(gateExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + gateExecId));
        gate.setDecision(decision);
        gate.setResult(result);
        gate.setArtifactRefs(artifactRefs);
        execRepo.save(gate);
    }

    private StepResult completedReplay(NodeExecution gate, GateStamp stamp) {
        if (gate.getDecision() == null) {
            throw new ConflictException(
                    "Approval " + stamp.getId() + " was applied elsewhere, not at gate " + gate.getId());
        }
        return new StepResult(COMPLETED, gate.getResult(), attachmentsOf(stamp), null);
    }

    private MergeOutcome recordMerges(UUID runId, JsonNode config, List<WorkerPrResult> results, UUID approvalId) {
        Optional<PullRequestMergeMethod> method = PullRequestMergeMethod.fromConfigOverrides(config);
        if (method.isEmpty()) {
            if (!results.isEmpty()) {
                log.warn("Run {}: ignoring {} merge results for a gate that merges nothing", runId, results.size());
            }
            return null;
        }
        MergeOutcome outcome = merge.outcomeOf(runId, merge.planMerge(runId, method.get()), results, approvalId);
        merge.recordOutcome(runId, outcome);
        return outcome;
    }

    private StepResult refuseFromHold(UUID runId, UUID gateExecId, UUID approvalId, String reason) {
        refuse(runId, gateExecId, approvalId, reason, null);
        return refusedStep(reason);
    }

    private void release(UUID runId, UUID gateExecId, UUID approvalId, String reason) {
        try {
            tx.executeWithoutResult(status -> {
                gateRecheck.voidStamp(approvalId, reason, gateExecId);
                claims.compareAndSetStatus(
                        gateExecId, NodeExecutionStatus.finalizing, NodeExecutionStatus.awaiting_human);
            });
        } catch (RuntimeException e) {
            log.error(
                    "Could not release gate {} of run {} after a failed signal: {}",
                    gateExecId,
                    runId,
                    e.getMessage(),
                    e);
        }
    }

    private void publish(UUID runId, UUID gateExecId, NodeExecutionStatus status) {
        try {
            events.publishNodeStatusChanged(runId, gateExecId, status.name());
        } catch (RuntimeException e) {
            log.warn("Could not publish {} for gate {} of run {}: {}", status, gateExecId, runId, e.getMessage());
        }
    }

    private void auditHeld(UUID runId, GateStamp stamp, DriftCheck check) {
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("gate", stamp.getGateLabel());
            json.put("stampId", stamp.getId().toString());
            json.put("round", stamp.getRecheckRounds());
            ArrayNode repos = json.putArray("staleRepos");
            check.staleRepos().forEach(r -> repos.add(r.ownerRepo()));
            auditSink.record(
                    AuditSink.GATE_APPROVAL_HELD, "workflow_run", runId, objectMapper.writeValueAsString(json));
        } catch (Exception e) {
            log.error("Could not record gate-approval-held audit event for run {}: {}", runId, e.getMessage(), e);
        }
    }

    private static PlanResponse refusedPlan(String reason) {
        return new PlanResponse(REFUSED, reason, List.of(), List.of(), null, false);
    }

    private static StepResult refusedStep(String reason) {
        return new StepResult(REFUSED, null, null, reason);
    }

    private static String withFeedback(String result, String feedback) {
        if (feedback == null || feedback.isBlank()) {
            return result;
        }
        return result != null && !result.isBlank() ? result + "\n\n## Reviewer Feedback\n\n" + feedback : feedback;
    }

    private static String attachmentsOf(GateStamp stamp) {
        return stamp.getAttachmentRefs() == null ? "{}" : stamp.getAttachmentRefs();
    }

    private static List<CompareResult> toCompareResults(List<CompareResultItem> items) {
        return items == null
                ? List.of()
                : items.stream()
                        .map(i -> new CompareResult(i.gitRepoId(), i.aheadBy(), i.baseGone()))
                        .toList();
    }

    /** One result per row, the first reported: {@link PullRequestMergeService#outcomeOf} would count a repeat twice. */
    private static List<WorkerPrResult> toWorkerResults(List<PrResultItem> items) {
        if (items == null) {
            return List.of();
        }
        Map<UUID, WorkerPrResult> byRow = new LinkedHashMap<>();
        for (PrResultItem item : items) {
            if (item == null || item.rowId() == null) {
                throw new BadRequestException("A pull request result is missing its rowId");
            }
            byRow.putIfAbsent(
                    item.rowId(),
                    new WorkerPrResult(item.rowId(), kind(item.kind()), item.mergedAt(), item.mergeSha()));
        }
        return List.copyOf(byRow.values());
    }

    private static Kind kind(String value) {
        for (Kind k : Kind.values()) {
            if (k.name().equals(value)) {
                return k;
            }
        }
        throw new BadRequestException("Unknown pull request result kind: " + value);
    }
}
