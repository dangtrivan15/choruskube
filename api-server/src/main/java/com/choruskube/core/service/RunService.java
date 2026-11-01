package com.choruskube.core.service;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.credential.CredentialPreflightChecker;
import com.choruskube.core.dto.*;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.GateClaimConflictException;
import com.choruskube.core.exception.GateRecheckExhaustedException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.PullRequestMergeMethod;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.observability.UsageSink;
import com.choruskube.core.repository.*;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.specification.LikePatterns;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.annotation.Nullable;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RunService {

    private static final Logger logger = LoggerFactory.getLogger(RunService.class);

    static final int RUN_NAME_MAX_LENGTH = 30;
    static final String ASYNC_FINALIZE = "async";
    static final String RUN_NAME_TRUNCATION_MARKER = "…";

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository execRepo;
    private final TemplateEdgeRepository edgeRepo;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final GraphTemplateRepository graphTemplateRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final GraphValidationService validationService;
    private final ExecutionLogRepository executionLogRepo;
    private final ObjectMapper objectMapper;
    private final RunEventPublisher eventPublisher;
    private final GitRepoRepository gitRepoRepo;
    private final AuthorizationService authService;
    private final Optional<QuotaChecker> quotaService;
    private final RunPlacementService placements;
    private final WorkflowClientRegistry workflowClients;
    private final UsageSink usageSink;
    private final AuditSink auditSink;
    private final StoragePrefixResolver storagePrefixResolver;
    private final RunPullRequestService runPullRequestService;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final RepoGroupMemberRepository repoGroupMemberRepo;
    private final CredentialPreflightChecker credentialPreflightChecker;
    private final UploadService uploadService;
    private final TaskRepository taskRepo;
    private final StoryRepository storyRepo;
    private final EpicRepository epicRepo;
    private final ArtifactResolutionService artifactResolutionService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ScopeProvider scopeProvider;
    private final DecisionOptionsResolver decisionOptionsResolver;
    private final RoadmapCandidateMaterializer roadmapCandidateMaterializer;
    private final RoadmapCandidatesArtifactResolver roadmapCandidatesArtifactResolver;
    private final NodeExecutionClaimService nodeExecutionClaimService;
    private final EscalationContextResolver escalationContextResolver;
    private final RoadmapProposalValidator roadmapProposalValidator;
    private final PullRequestMergeService pullRequestMergeService;
    private final TaskSettlementService taskSettlement;
    private final GateDriftCheckService gateDriftCheck;
    private final GateRecheckService gateRecheck;
    private final GateFinalizeService gateFinalize;

    @Value("${temporal.task-queue}")
    private String taskQueue;

    public RunService(
            WorkflowRunRepository runRepo,
            NodeExecutionRepository execRepo,
            TemplateEdgeRepository edgeRepo,
            GraphSnapshotBuilder snapshotBuilder,
            GraphTemplateRepository graphTemplateRepo,
            TemplateNodeRepository templateNodeRepo,
            GraphValidationService validationService,
            ExecutionLogRepository executionLogRepo,
            ObjectMapper objectMapper,
            RunEventPublisher eventPublisher,
            GitRepoRepository gitRepoRepo,
            AuthorizationService authService,
            Optional<QuotaChecker> quotaService,
            RunPlacementService placements,
            WorkflowClientRegistry workflowClients,
            UsageSink usageSink,
            AuditSink auditSink,
            StoragePrefixResolver storagePrefixResolver,
            RunPullRequestService runPullRequestService,
            SoftwareProjectRepository softwareProjectRepo,
            RepoGroupMemberRepository repoGroupMemberRepo,
            CredentialPreflightChecker credentialPreflightChecker,
            UploadService uploadService,
            TaskRepository taskRepo,
            StoryRepository storyRepo,
            EpicRepository epicRepo,
            ArtifactResolutionService artifactResolutionService,
            ApplicationEventPublisher applicationEventPublisher,
            ScopeProvider scopeProvider,
            DecisionOptionsResolver decisionOptionsResolver,
            @Lazy RoadmapCandidateMaterializer roadmapCandidateMaterializer,
            RoadmapCandidatesArtifactResolver roadmapCandidatesArtifactResolver,
            NodeExecutionClaimService nodeExecutionClaimService,
            EscalationContextResolver escalationContextResolver,
            @Lazy RoadmapProposalValidator roadmapProposalValidator,
            PullRequestMergeService pullRequestMergeService,
            TaskSettlementService taskSettlement,
            GateDriftCheckService gateDriftCheck,
            GateRecheckService gateRecheck,
            @Lazy GateFinalizeService gateFinalize) {
        this.runRepo = runRepo;
        this.execRepo = execRepo;
        this.edgeRepo = edgeRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.graphTemplateRepo = graphTemplateRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.validationService = validationService;
        this.executionLogRepo = executionLogRepo;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.gitRepoRepo = gitRepoRepo;
        this.authService = authService;
        this.quotaService = quotaService;
        this.placements = placements;
        this.workflowClients = workflowClients;
        this.usageSink = usageSink;
        this.auditSink = auditSink;
        this.storagePrefixResolver = storagePrefixResolver;
        this.runPullRequestService = runPullRequestService;
        this.softwareProjectRepo = softwareProjectRepo;
        this.repoGroupMemberRepo = repoGroupMemberRepo;
        this.credentialPreflightChecker = credentialPreflightChecker;
        this.uploadService = uploadService;
        this.taskRepo = taskRepo;
        this.storyRepo = storyRepo;
        this.epicRepo = epicRepo;
        this.artifactResolutionService = artifactResolutionService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.scopeProvider = scopeProvider;
        this.decisionOptionsResolver = decisionOptionsResolver;
        this.roadmapCandidateMaterializer = roadmapCandidateMaterializer;
        this.roadmapCandidatesArtifactResolver = roadmapCandidatesArtifactResolver;
        this.nodeExecutionClaimService = nodeExecutionClaimService;
        this.escalationContextResolver = escalationContextResolver;
        this.roadmapProposalValidator = roadmapProposalValidator;
        this.pullRequestMergeService = pullRequestMergeService;
        this.taskSettlement = taskSettlement;
        this.gateDriftCheck = gateDriftCheck;
        this.gateRecheck = gateRecheck;
        this.gateFinalize = gateFinalize;
    }

    @Transactional
    public RunResponse startRun(CreateRunRequest request) {
        GraphTemplate template = graphTemplateRepo
                .findById(request.graphTemplateId())
                .orElseThrow(() -> new NotFoundException("Template not found"));
        // Template is exposed in run detail response and will be executed; verify read access.
        authService.checkTemplateReadAccess(template.isSystem(), template.getId());

        List<TemplateNode> templateNodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        List<TemplateEdge> edges = edgeRepo.findByGraphTemplateId(template.getId());
        ValidationResponse validation = validationService.validate(templateNodes, edges);
        if (!validation.valid()) {
            throw new ValidationException(validation.errors());
        }

        Map<String, Object> mergedInputs = mergeInputs(template, request.inputs());

        validateInputs(template.getInputSchema(), mergedInputs);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(request.graphTemplateId());
        run.setName(trimName(request.name()));
        try {
            run.setInputs(objectMapper.writeValueAsString(mergedInputs));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize inputs", e);
        }

        // input_artifact_refs is intentionally left at the entity default ("{}") here.
        // Staged attachments live under {orgSlug}/staging/{stagingId}/, which is outside
        // the agent's per-execution presign scope. We move them into the run-scoped
        // prefix below — once we have a runId — and persist the rewritten refs as part
        // of the second save (alongside externalRunId) so the column never holds a
        // staging path the agent would later be forbidden from reading.

        quotaService.ifPresent(QuotaChecker::checkRunQuota); // throws QuotaExceededException (429)

        run = runRepo.save(run);

        // Publish ownership immediately after the first save so the ownership row exists before
        // any downstream seam resolves the run's org (checkPreflight, storagePrefixForRun).
        // startRun is @Transactional and the listener runs in the same tx (REQUIRED/MANDATORY),
        // so TenantContext is available on this thread and the row is visible within the tx.
        // A failed check below rolls back everything — no orphan run row or ownership row is left.
        applicationEventPublisher.publishEvent(MappableCreated.of("workflow_run", run.getId()));

        // Placed before the preflight so a seam deciding by where the run executes (e.g. a Fleet
        // pin) can read that answer; a refusal below rolls the placement back with the run.
        RunPlacement placement = placements.placeFor(run.getId());

        credentialPreflightChecker.checkPreflight(run.getId()); // throws InvalidCredentialException (400)

        String inputRefs = request.inputAttachmentRefs();
        if (inputRefs != null && !inputRefs.isBlank() && !"{}".equals(inputRefs)) {
            String orgSlug = storagePrefixResolver.storagePrefixForRun(run.getId());
            try {
                run.setInputArtifactRefs(uploadService.copyStagingToRun(orgSlug, run.getId(), inputRefs));
            } catch (Exception e) {
                throw new RuntimeException("Failed to move staged attachments into run scope: " + e.getMessage(), e);
            }
        }

        String workflowId = "choruskube-run-" + run.getId();
        run.setExternalRunId(workflowId);
        // Saved with the workflow id, not derived later: the pair is the workflow's address, and
        // deleting an organization cascades away the rows a later lookup would read.
        run.setTemporalNamespace(placement.namespace());
        run = runRepo.save(run);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .build();

        final WorkflowRun committedRun = run;
        final Map<String, Object> finalWorkflowParams = buildWorkflowParams(run, placement);
        final WorkflowStub finalWorkflow =
                workflowClients.clientFor(placement.namespace()).newUntypedWorkflowStub("DAGExecutorWorkflow", options);

        Runnable sideEffects = () -> {
            finalWorkflow.start(finalWorkflowParams);
            eventPublisher.publishRunStatusChanged(committedRun.getId(), "pending");
            usageSink.record(UsageSink.RUN_STARTED, "workflow_run", committedRun.getId(), null);
        };

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sideEffects.run();
            }
        });

        return toResponse(committedRun, Collections.emptyList(), null);
    }

    public RunResponse getRun(UUID id) {
        WorkflowRun run =
                runRepo.findById(id).orElseThrow(() -> new NotFoundException("Workflow run not found: " + id));
        authService.checkOrgAccess("workflow_run", id);
        List<NodeExecution> execs = execRepo.findByWorkflowRunId(id);
        RunTaskSummary taskSummary = buildTaskSummary(run);
        return toResponse(run, execs, taskSummary);
    }

    public Page<RunSummary> listRuns(String status, String name, Pageable pageable) {
        Specification<WorkflowRun> spec = scopeProvider.scope(WorkflowRun.class);
        if (status != null && !status.isBlank()) {
            WorkflowRunStatus parsed = WorkflowRunStatus.valueOf(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), parsed));
        }
        if (name != null && !name.isBlank()) {
            String pattern = LikePatterns.containsIgnoreCase(name);
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }
        Page<WorkflowRun> page = runRepo.findAll(spec, pageable);

        Set<UUID> templateIds =
                page.stream().map(WorkflowRun::getGraphTemplateId).collect(Collectors.toSet());
        Map<UUID, String> templateNames = graphTemplateRepo.findAllById(templateIds).stream()
                .collect(Collectors.toMap(GraphTemplate::getId, GraphTemplate::getName));

        Set<UUID> taskIds = page.stream()
                .map(WorkflowRun::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Task> taskById = taskIds.isEmpty()
                ? Map.of()
                : taskRepo.findAllById(taskIds).stream().collect(Collectors.toMap(Task::getId, t -> t));

        Map<UUID, UUID> projectIdByRunId = new HashMap<>();
        for (WorkflowRun run : page.getContent()) {
            Task task = run.getTaskId() != null ? taskById.get(run.getTaskId()) : null;
            UUID projectId =
                    (task != null) ? task.getSoftwareProjectId() : extractSoftwareProjectIdFromInputs(run.getInputs());
            if (projectId != null) projectIdByRunId.put(run.getId(), projectId);
        }

        // Batch-fetch the software-project entities (soft-deleted ones are excluded by @SQLRestriction)
        Map<UUID, SoftwareProject> projectById = softwareProjectRepo.findAllById(projectIdByRunId.values()).stream()
                .collect(Collectors.toMap(SoftwareProject::getId, p -> p));

        Map<UUID, SoftwareProjectRef> softwareProjectByRunId = new HashMap<>();
        projectIdByRunId.forEach((runId, projId) -> {
            SoftwareProject sp = projectById.get(projId);
            if (sp != null) {
                softwareProjectByRunId.put(runId, toSoftwareProjectRef(sp));
            }
        });

        return page.map(run -> {
            String templateName = templateNames.getOrDefault(run.getGraphTemplateId(), "Unknown");
            return new RunSummary(
                    run.getId(),
                    run.getGraphTemplateId(),
                    templateName,
                    run.getName(),
                    run.getStatus().name(),
                    run.getStartedAt(),
                    run.getCompletedAt(),
                    run.getCreatedAt(),
                    run.getAutopilotId(),
                    softwareProjectByRunId.get(run.getId()));
        });
    }

    public void renameRun(UUID id, String name) {
        WorkflowRun run = findRunOrThrow(id);
        authService.checkOrgAccess("workflow_run", id);
        run.setName(trimName(name));
        runRepo.save(run);
    }

    static @Nullable String trimName(@Nullable String name) {
        if (name == null) {
            return null;
        }
        if (name.length() <= RUN_NAME_MAX_LENGTH) {
            return name;
        }
        // Reserve one char of the budget for the truncation marker so the stored
        // name is still bounded at RUN_NAME_MAX_LENGTH and the user sees a visual
        // hint that the original was longer.
        return name.substring(0, RUN_NAME_MAX_LENGTH - RUN_NAME_TRUNCATION_MARKER.length())
                + RUN_NAME_TRUNCATION_MARKER;
    }

    public void pauseRun(UUID id) {
        WorkflowRun run = findRunOrThrow(id);
        authService.checkOrgAccess("workflow_run", id);
        rejectIfTerminal(run, "pause");
        signalWorkflowOrThrow(run, "pause");
        auditSink.record(AuditSink.RUN_PAUSED, "workflow_run", id, null);
    }

    public void resumeRun(UUID id) {
        WorkflowRun run = findRunOrThrow(id);
        authService.checkOrgAccess("workflow_run", id);
        rejectIfTerminal(run, "resume");
        signalWorkflowOrThrow(run, "resume");
        auditSink.record(AuditSink.RUN_RESUMED, "workflow_run", id, null);
    }

    @Transactional
    public void cancelRun(UUID id) {
        WorkflowRun run = findRunOrThrow(id);
        authService.checkOrgAccess("workflow_run", id);
        rejectIfTerminal(run, "cancel");
        signalWorkflow(run, "cancel");
        run.setStatus(WorkflowRunStatus.cancelled);
        runRepo.save(run);
        gateRecheck.voidLiveForRun(id, GateRecheckService.RUN_ENDED);
        taskSettlement.closeIfSettledBy(run);
        auditSink.record(AuditSink.RUN_CANCELLED, "workflow_run", id, null);

        // Mark any non-terminal node executions as skipped so they don't
        // linger on the approvals page. This is necessary because the
        // Temporal workflow may have already completed or been cancelled
        // externally, in which case the orchestrator's cleanup never runs.
        Set<NodeExecutionStatus> activeStatuses = Set.of(
                NodeExecutionStatus.pending,
                NodeExecutionStatus.running,
                NodeExecutionStatus.awaiting_human,
                NodeExecutionStatus.finalizing);
        for (NodeExecution exec : execRepo.findByWorkflowRunId(id)) {
            if (activeStatuses.contains(exec.getStatus())) {
                exec.setStatus(NodeExecutionStatus.skipped);
                execRepo.save(exec);
                eventPublisher.publishNodeStatusChanged(id, exec.getId(), "skipped");
            }
        }
    }

    public void retryNode(UUID runId, UUID nodeExecId) {
        WorkflowRun run = findRunOrThrow(runId);
        authService.checkOrgAccess("workflow_run", runId);

        if (run.getStatus() != WorkflowRunStatus.awaiting_retry) {
            throw new ValidationException(
                    List.of("Cannot retry node: run status is " + run.getStatus() + ", expected awaiting_retry"));
        }

        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        NodeExecutionUtil.requireInRun(exec, runId);

        if (exec.getStatus() != NodeExecutionStatus.failed) {
            throw new ValidationException(
                    List.of("Cannot retry node: node status is " + exec.getStatus() + ", expected failed"));
        }

        WorkflowStub stub =
                workflowClients.clientFor(run.getTemporalNamespace()).newUntypedWorkflowStub(run.getExternalRunId());
        Map<String, String> payload =
                Map.of("templateNodeId", exec.getTemplateNodeId().toString());
        stub.signal("retry-node", payload);
    }

    /**
     * Send a signal to the Temporal workflow. Returns true if the signal was sent,
     * false if the workflow has already completed (NOT_FOUND).
     * Other Temporal errors (connectivity, etc.) propagate as-is.
     */
    private boolean signalWorkflow(WorkflowRun run, String signalName) {
        try {
            WorkflowStub stub = workflowClients
                    .clientFor(run.getTemporalNamespace())
                    .newUntypedWorkflowStub(run.getExternalRunId());
            stub.signal(signalName);
            return true;
        } catch (WorkflowNotFoundException e) {
            logger.warn("Temporal workflow already completed for run {}", run.getId());
            return false;
        }
    }

    private void signalWorkflowOrThrow(WorkflowRun run, String signalName) {
        if (!signalWorkflow(run, signalName)) {
            throw new ValidationException(
                    List.of("Cannot " + signalName + " run: workflow has already completed in Temporal"));
        }
    }

    private void rejectIfTerminal(WorkflowRun run, String action) {
        if (run.getStatus() == WorkflowRunStatus.completed
                || run.getStatus() == WorkflowRunStatus.failed
                || run.getStatus() == WorkflowRunStatus.cancelled) {
            throw new ValidationException(List.of("Cannot " + action + " a run that is already " + run.getStatus()));
        }
    }

    public SignalResponse signalHumanDecision(UUID runId, UUID nodeExecId, SignalRequest request) {
        WorkflowRun run = findRunOrThrow(runId);
        authService.checkOrgAccess("workflow_run", runId);
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        NodeExecutionUtil.requireInRun(exec, runId);
        return decideGate(run, exec, request, null);
    }

    /**
     * Re-applies an approval held for a re-check once its gate awaits a decision again. Runs with
     * no request behind it, inside {@link RunScopeBinder}. Every way it cannot apply voids the stamp
     * and leaves the gate to a human, except losing the claim to a reviewer, whose decision stands.
     */
    public void applyHeldApproval(UUID stampId) {
        GateStamp stamp = gateRecheck.findPending(stampId).orElse(null);
        if (stamp == null) {
            return;
        }
        WorkflowRun run = findRunOrThrow(stamp.getWorkflowRunId());
        NodeExecution gate =
                execRepo.findByWorkflowRunIdAndStatus(run.getId(), NodeExecutionStatus.awaiting_human).stream()
                        .filter(e -> stamp.getGateTemplateNodeId().equals(e.getTemplateNodeId()))
                        .findFirst()
                        .orElse(null);
        if (gate == null) {
            return;
        }
        Optional<String> reasonNotTo = gateRecheck.reasonNotToReapply(stamp, readSnapshot(run));
        if (reasonNotTo.isPresent()) {
            parkHeldApproval(stamp, gate, reasonNotTo.get());
            return;
        }
        try {
            decideGate(
                    run,
                    gate,
                    new SignalRequest("approved", stamp.getFeedback(), stamp.getAttachmentRefs(), null),
                    stamp);
        } catch (GateClaimConflictException e) {
            logger.info(
                    "Held approval {} lost the claim on gate {}; the decision that won stands", stampId, gate.getId());
        } catch (RuntimeException e) {
            parkHeldApproval(stamp, gate, e.getMessage());
        }
    }

    private void parkHeldApproval(GateStamp stamp, NodeExecution gate, String reason) {
        gateRecheck.voidStamp(stamp.getId(), reason, gate.getId());
        logger.info("Held approval {} for run {} not applied: {}", stamp.getId(), stamp.getWorkflowRunId(), reason);
        eventPublisher.publishNodeStatusChanged(
                stamp.getWorkflowRunId(), gate.getId(), NodeExecutionStatus.awaiting_human.name());
    }

    private SignalResponse decideGate(WorkflowRun run, NodeExecution exec, SignalRequest request, GateStamp heldStamp) {
        UUID runId = run.getId();
        UUID nodeExecId = exec.getId();

        // Only a gate opened on the async protocol has a workflow that finishes an approval in steps;
        // a gate opened before it waits for a final decision signal, so it must stay on the path below.
        if (ASYNC_FINALIZE.equals(exec.getFinalizeMode()) && heldStamp == null) {
            JsonNode snapshot = readSnapshot(run);
            String decision = validateDecisionAgainstEdges(snapshot, exec.getTemplateNodeId(), request.decision());
            if ("approved".equalsIgnoreCase(decision) && needsFinalizing(snapshot, exec.getTemplateNodeId())) {
                JsonNode nodeConfig = decisionOptionsResolver.findNodeConfigOverrides(
                        snapshot.get("nodes"), exec.getTemplateNodeId());
                Optional<RoadmapMaterializeMode> mode = materializeMode(snapshot, exec.getTemplateNodeId());
                RoadmapCandidatesDocument doc = mode.isEmpty()
                        ? null
                        : request.editedCandidates() != null
                                ? request.editedCandidates()
                                : roadmapCandidatesArtifactResolver.resolve(runId, exec.getTemplateNodeId());
                // Rejected here, the reviewer's edits stay on screen to fix; refused after the claim,
                // the gate reopens with the server's document and the edits are lost.
                if (mode.isPresent()) {
                    requireValidRoadmap(runId, mode.get(), doc);
                }
                return gateFinalize.requestApproval(
                        run,
                        exec,
                        request,
                        snapshot,
                        DriftRecheckConfig.fromConfigOverrides(nodeConfig).orElse(null),
                        doc);
            }
        }

        // Atomically claim the node before doing anything else. Two concurrent/duplicate
        // submissions (double-click, two open tabs, a client retry) would otherwise both pass
        // validation below and both independently materialize the Roadmap Provisioner candidate
        // breakdown, silently creating duplicate Epic/Story/Task rows with nothing surfaced to the
        // reviewer. This is a single atomic UPDATE ... WHERE status = awaiting_human — only one
        // concurrent caller can win it; the other sees 0 rows affected and is rejected outright.
        int claimed = nodeExecutionClaimService.compareAndSetStatus(
                nodeExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running);
        if (claimed == 0) {
            throw new GateClaimConflictException(
                    "Cannot signal decision: node execution is not awaiting a human decision "
                            + "(already decided, or a concurrent request already claimed it)");
        }

        // From here on, any failure (bad decision string, merge refusal, materialization error,
        // Temporal signal failure) must release the claim back to awaiting_human so the request
        // stays retryable — e.g. a typo'd decision string is a routine, expected error today and
        // must not permanently strand the gate. Merging is safe to repeat here because it skips
        // whatever GitHub already reports merged. This only reopens a (much narrower, pre-existing)
        // window where a retry after materialization already succeeded but the signal itself then
        // failed could re-materialize;
        // the case this guard exists for — two concurrent/duplicate submissions racing each other —
        // is fully closed by the claim above.
        try {
            JsonNode snapshot = readSnapshot(run);

            String validatedDecision =
                    validateDecisionAgainstEdges(snapshot, exec.getTemplateNodeId(), request.decision());

            if (heldStamp == null) {
                gateRecheck.voidPendingFor(runId, exec.getTemplateNodeId(), GateRecheckService.REVIEWER_DECIDED);
            }

            String assembledResult = exec.getResult();
            if (request.feedback() != null && !request.feedback().isBlank()) {
                if (assembledResult != null && !assembledResult.isBlank()) {
                    assembledResult = assembledResult + "\n\n## Reviewer Feedback\n\n" + request.feedback();
                } else {
                    assembledResult = request.feedback();
                }
            }

            // Deterministic materialization: on approval of a gate configured for it,
            // turn the reviewed (possibly reviewer-edited) roadmap candidate document
            // directly into Epic/Story/Task rows — through the same write path a human uses — in
            // the same request that handles the decision signal, rather than via a second AI
            // agent.
            if ("approved".equalsIgnoreCase(validatedDecision)) {
                JsonNode nodeConfig = decisionOptionsResolver.findNodeConfigOverrides(
                        snapshot.get("nodes"), exec.getTemplateNodeId());
                Optional<RoadmapMaterializeMode> mode = materializeMode(snapshot, exec.getTemplateNodeId());
                Optional<PullRequestMergeMethod> mergeMethod = PullRequestMergeMethod.fromConfigOverrides(nodeConfig);

                // Validate before merging anything: a rejected roadmap proposal must never leave
                // pull requests merged while the gate is still open. Materialization itself (a
                // non-idempotent write) still waits until after the merge below.
                RoadmapCandidatesDocument source = null;
                MaterializationPlan plan = null;
                if (mode.isPresent()) {
                    // A present-but-empty editedCandidates means the reviewer deliberately cleared
                    // the breakdown (e.g. rejecting every candidate while still approving the gate
                    // for some other reason) and must NOT fall back to the original analyzer
                    // artifact — only a genuinely absent field (no edits submitted at all) does that.
                    // A held approval materializes the document the reviewer approved, never a newer
                    // copy the re-check wrote that nobody reviewed.
                    source = heldStamp != null
                            ? gateRecheck.approvedCandidates(heldStamp)
                            : request.editedCandidates() != null
                                    ? request.editedCandidates()
                                    : roadmapCandidatesArtifactResolver.resolve(runId, exec.getTemplateNodeId());
                    plan = prepareMaterialization(runId, mode.get(), source);
                }

                Optional<DriftRecheckConfig> recheck = DriftRecheckConfig.fromConfigOverrides(nodeConfig);
                if (recheck.isPresent()) {
                    GateDriftCheckService.DriftCheck drift =
                            gateDriftCheck.check(runId, exec.getTemplateNodeId(), snapshot);
                    if (drift.stale()) {
                        return holdForRecheck(run, exec, snapshot, request, heldStamp, recheck.get(), drift, source);
                    }
                }

                if (mergeMethod.isPresent()) {
                    PullRequestMergeService.MergeOutcome outcome = pullRequestMergeService.mergeAll(
                            runId, mergeMethod.get(), heldStamp != null ? heldStamp.getId() : null);
                    // Commits here, before the signal, only because this method has no transaction:
                    // a signalled run can finish at once, and its Task closes only on merges that
                    // are already committed (TaskSettlementService).
                    pullRequestMergeService.recordOutcome(runId, outcome);
                    assembledResult = appendNote(assembledResult, pullRequestMergeService.note(outcome));
                }

                if (plan != null && plan.runnable()) {
                    assembledResult = appendNote(assembledResult, materializeAndNote(runId, plan));
                } else if (plan != null) {
                    assembledResult = appendNote(assembledResult, plan.fixedNote());
                }
            }

            signalGate(run, nodeExecId, validatedDecision, assembledResult, request.attachmentRefs(), null);
        } catch (RuntimeException e) {
            nodeExecutionClaimService.compareAndSetStatus(
                    nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
            throw e;
        }
        afterSignal(runId, nodeExecId, () -> {
            if (heldStamp != null) {
                gateRecheck.consume(heldStamp.getId());
            }
        });
        return SignalResponse.signaled();
    }

    /**
     * Bookkeeping after a decision was delivered. A failure is logged and never thrown: releasing
     * the claim or reporting an error now would misstate a decision the workflow already received.
     */
    private void afterSignal(UUID runId, UUID nodeExecId, Runnable bookkeeping) {
        try {
            bookkeeping.run();
        } catch (RuntimeException e) {
            logger.error(
                    "Bookkeeping after signalling gate {} of run {} failed: {}", nodeExecId, runId, e.getMessage(), e);
        }
        try {
            eventPublisher.publishNodeStatusChanged(runId, nodeExecId, "signaled");
        } catch (RuntimeException e) {
            logger.warn("Could not publish the signal of gate {} of run {}: {}", nodeExecId, runId, e.getMessage());
        }
    }

    /**
     * Holds an approval whose run is stale: fires the gate's re-check route with system-written
     * guidance and records the stamp, which {@code GateStampReconciler} re-applies once the gate
     * awaits again. Nothing is merged or materialized here.
     */
    private SignalResponse holdForRecheck(
            WorkflowRun run,
            NodeExecution exec,
            JsonNode snapshot,
            SignalRequest request,
            GateStamp heldStamp,
            DriftRecheckConfig recheck,
            GateDriftCheckService.DriftCheck drift,
            RoadmapCandidatesDocument approvedCandidates) {
        UUID runId = run.getId();
        if (heldStamp != null && cannotBeFixedByARecheck(drift)) {
            throw new GateRecheckExhaustedException("The re-check could not tell which main commit was tested ("
                    + drift.summary() + "). The project's agent image may predate base recording, so rebuild it on "
                    + "the current claude-code image.");
        }
        int round = heldStamp == null ? 1 : heldStamp.getRecheckRounds() + 1;
        if (round > GateDriftCheckService.MAX_RECHECK_ROUNDS) {
            throw new GateRecheckExhaustedException("The default branch moved again after "
                    + GateDriftCheckService.MAX_RECHECK_ROUNDS + " re-check rounds: " + drift.summary()
                    + ". Approve again to start a new re-check.");
        }
        String route = validateDecisionAgainstEdges(snapshot, exec.getTemplateNodeId(), recheck.route());
        String guidanceRefs;
        try {
            guidanceRefs = uploadService.uploadGateText(
                    storagePrefixResolver.storagePrefixForRun(runId),
                    runId,
                    exec.getId(),
                    "human_guidance.md",
                    gateDriftCheck.guidance(runId, drift, round));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not store the re-check guidance for run " + runId, e);
        }
        // Written before the signal: once the re-check starts, nothing else would remember the approval.
        GateStamp stamp = heldStamp == null
                ? gateRecheck.hold(
                        runId,
                        exec,
                        nodeLabel(snapshot, exec.getTemplateNodeId()),
                        recheck.stamp(),
                        request.feedback(),
                        request.attachmentRefs(),
                        approvedCandidates)
                : gateRecheck.holdAgain(heldStamp.getId());
        String note = "Approval held for a re-check against current main (round " + round + " of "
                + GateDriftCheckService.MAX_RECHECK_ROUNDS + "): " + drift.summary() + ".";
        try {
            signalGate(run, exec.getId(), route, appendNote(exec.getResult(), note), guidanceRefs, null);
        } catch (RuntimeException e) {
            if (heldStamp == null) {
                voidQuietly(stamp, "Starting the re-check failed: " + e.getMessage(), exec.getId());
            }
            throw e;
        }
        afterSignal(runId, exec.getId(), () -> auditHeld(runId, stamp, drift));
        return SignalResponse.held(drift);
    }

    /** Stale only for want of a record a re-check would not produce either: the pods record nothing. */
    static boolean cannotBeFixedByARecheck(GateDriftCheckService.DriftCheck drift) {
        return drift.staleRepos().stream()
                .allMatch(r -> r.reason() == GateDriftCheckService.Reason.NO_RECORD
                        || r.reason() == GateDriftCheckService.Reason.BASE_GONE);
    }

    private void voidQuietly(GateStamp stamp, String reason, UUID gateExecId) {
        try {
            gateRecheck.voidStamp(stamp.getId(), reason, gateExecId);
        } catch (RuntimeException e) {
            logger.error("Could not void held approval {}: {}", stamp.getId(), e.getMessage(), e);
        }
    }

    /** {@code approvalId}: the approval the workflow finalizes in steps; null signals a final decision. */
    void signalGate(
            WorkflowRun run, UUID nodeExecId, String decision, String result, String attachmentRefs, UUID approvalId) {
        WorkflowStub stub =
                workflowClients.clientFor(run.getTemporalNamespace()).newUntypedWorkflowStub(run.getExternalRunId());
        HumanDecisionPayload payload = new HumanDecisionPayload(
                nodeExecId.toString(),
                decision,
                result != null ? result : "",
                attachmentRefs != null ? attachmentRefs : "{}",
                approvalId != null ? approvalId.toString() : null);
        stub.signal("human-decision-" + nodeExecId.toString(), payload);
    }

    /** Whether approving this gate leaves work to finish after the click: merging, a roadmap write or a re-check. */
    boolean needsFinalizing(JsonNode snapshot, UUID templateNodeId) {
        JsonNode config = decisionOptionsResolver.findNodeConfigOverrides(snapshot.get("nodes"), templateNodeId);
        return config != null
                && (config.has(PullRequestMergeMethod.CONFIG_KEY)
                        || config.has(RoadmapMaterializeMode.CONFIG_KEY)
                        || config.has(DriftRecheckConfig.CONFIG_KEY));
    }

    static String nodeLabel(JsonNode snapshot, UUID templateNodeId) {
        for (JsonNode node : snapshot.path("nodes")) {
            if (templateNodeId.toString().equals(node.path("template_node_id").asText())) {
                return node.path("label").asText(templateNodeId.toString());
            }
        }
        return templateNodeId.toString();
    }

    private void auditHeld(UUID runId, GateStamp stamp, GateDriftCheckService.DriftCheck drift) {
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("gate", stamp.getGateLabel());
            json.put("stampId", stamp.getId().toString());
            json.put("round", stamp.getRecheckRounds());
            ArrayNode repos = json.putArray("staleRepos");
            drift.staleRepos().forEach(r -> repos.add(r.ownerRepo()));
            auditSink.record(
                    AuditSink.GATE_APPROVAL_HELD, "workflow_run", runId, objectMapper.writeValueAsString(json));
        } catch (Exception e) {
            logger.error("Could not record gate-approval-held audit event for run {}: {}", runId, e.getMessage(), e);
        }
    }

    JsonNode readSnapshot(WorkflowRun run) {
        try {
            return objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build graph snapshot", e);
        }
    }

    /**
     * Validates a decision against the union of a node's outgoing edge conditions and its
     * configured {@code terminal_decisions}, via {@link DecisionOptionsResolver}.
     * Returns the canonical (case-matched) decision string.
     */
    String validateDecisionAgainstEdges(JsonNode snapshot, UUID templateNodeId, String decision) {
        try {
            List<String> validConditions = decisionOptionsResolver.resolve(snapshot, templateNodeId);
            if (validConditions.isEmpty()) {
                throw new BadRequestException("This node has no conditional edges");
            }
            String canonical = validConditions.stream()
                    .filter(c -> c.equalsIgnoreCase(decision))
                    .findFirst()
                    .orElseThrow(() -> new BadRequestException(
                            "Invalid decision: '" + decision + "'. Valid options: " + validConditions));
            return canonical;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to validate decision", e);
        }
    }

    Optional<RoadmapMaterializeMode> materializeMode(JsonNode snapshot, UUID templateNodeId) {
        JsonNode nodeConfigOverrides =
                decisionOptionsResolver.findNodeConfigOverrides(snapshot.get("nodes"), templateNodeId);
        return RoadmapMaterializeMode.fromConfigOverrides(nodeConfigOverrides);
    }

    /**
     * A validated-but-not-yet-performed materialization. {@code runnable()} tells the caller
     * whether to call {@link #materializeAndNote} at all: a plan built from a null source, or one
     * whose reviewed document creates nothing, resolves to {@link #fixedNote} instead, and
     * materialization never runs for it — so merging (a step that must run first; see {@link
     * #signalHumanDecision}) can never be followed by a roadmap write that turns out unnecessary.
     */
    record MaterializationPlan(
            boolean runnable, String fixedNote, RoadmapMaterializeMode mode, RoadmapCandidatesDocument source) {
        static MaterializationPlan fixedNote(String note) {
            return new MaterializationPlan(false, note, null, null);
        }

        static MaterializationPlan runnable(RoadmapMaterializeMode mode, RoadmapCandidatesDocument source) {
            return new MaterializationPlan(true, null, mode, source);
        }
    }

    /**
     * Validates an approved roadmap gate's reviewed document and decides whether materialization
     * has anything to do — without writing anything. Split from the write itself ({@link
     * #materializeAndNote}) so the roadmap proposal is confirmed valid before pull requests are
     * merged, while the non-idempotent write still waits until after the merge: a retry after a
     * merge failure must never risk creating the roadmap items twice.
     */
    MaterializationPlan prepareMaterialization(
            UUID runId, RoadmapMaterializeMode mode, RoadmapCandidatesDocument source) {
        if (source == null) {
            // null only for roadmap_extension with no submitted document: there is nothing to
            // validate or note without one, unlike roadmap_candidates' "skipped" note below, which
            // is the pre-existing behaviour for that mode.
            return MaterializationPlan.fixedNote(
                    mode == RoadmapMaterializeMode.roadmap_candidates
                            ? "Materialization skipped: no candidate breakdown was found for this run"
                            : null);
        }

        requireValidRoadmap(runId, mode, source);

        if (mode == RoadmapMaterializeMode.roadmap_extension) {
            RoadmapProposalValidator.Summary preview = roadmapProposalValidator.summarize(source);
            if (preview.newEpics() == 0
                    && preview.newStories() == 0
                    && preview.newTasks() == 0
                    && preview.dependencies() == 0) {
                // Whether the reviewer or the agent emptied the document cannot be told apart
                // here, so the note deliberately does not say which.
                return MaterializationPlan.fixedNote("Roadmap proposal: nothing to create");
            }
        }

        return MaterializationPlan.runnable(mode, source);
    }

    /**
     * Rejects a roadmap document with a structural violation, so approval is blocked outright
     * rather than silently creating a partial tree. A null document has nothing to validate.
     */
    void requireValidRoadmap(UUID runId, RoadmapMaterializeMode mode, RoadmapCandidatesDocument source) {
        if (source == null) {
            return;
        }
        List<String> violations =
                roadmapProposalValidator.validate(runId, source, mode, RoadmapProposalValidator.Strictness.GATE);
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    /** Performs a runnable plan's materialization and builds its result note. Not idempotent. */
    String materializeAndNote(UUID runId, MaterializationPlan plan) {
        MaterializationSummary summary = roadmapCandidateMaterializer.materialize(runId, plan.source(), plan.mode());
        return noteFor(plan.mode(), summary);
    }

    /** The write step's result: the same note text {@link #materializeAndNote} builds, plus what to hand the Worker. */
    record MaterializationOutcome(String note, List<TaskIssueTarget> issueTargets) {}

    /**
     * The split-protocol write step: materializes with {@link RoadmapCandidateMaterializer.IssueFiling#BY_WORKER},
     * so no GitHub call happens here — the returned targets are what the run's Worker must file.
     */
    MaterializationOutcome materializeForWorker(UUID runId, MaterializationPlan plan) {
        MaterializationSummary summary = roadmapCandidateMaterializer.materialize(
                runId, plan.source(), plan.mode(), RoadmapCandidateMaterializer.IssueFiling.BY_WORKER);
        return new MaterializationOutcome(noteFor(plan.mode(), summary), summary.issueTargets());
    }

    private static String noteFor(RoadmapMaterializeMode mode, MaterializationSummary summary) {
        String note = mode == RoadmapMaterializeMode.roadmap_candidates
                ? "Materialized " + summary.materializedCount() + " Epics ("
                        + summary.createdStoryIds().size() + " Stories, "
                        + summary.createdTaskIds().size() + " Tasks, "
                        + summary.createdMilestoneIds().size() + " Milestones, "
                        + summary.createdDependencyCount() + " dependency edges, "
                        + summary.skippedCount() + " skipped)"
                : "Roadmap extension approved: created " + summary.materializedCount() + " Epics, "
                        + summary.createdStoryIds().size() + " Stories, "
                        + summary.createdTaskIds().size() + " Tasks and " + summary.createdDependencyCount()
                        + " dependency edges (" + summary.skippedCount() + " skipped)";
        if (!summary.errors().isEmpty()) {
            note += "\nSkipped: " + summary.errors().stream().limit(5).collect(Collectors.joining("; "));
        }
        return note;
    }

    /** Appends {@code note} to {@code existing} with a blank line, or returns {@code existing} unchanged if null. */
    static String appendNote(String existing, String note) {
        if (note == null) {
            return existing;
        }
        return existing != null && !existing.isBlank() ? existing + "\n\n" + note : note;
    }

    public List<ExecutionLogResponse> getExecutionLogs(UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        WorkflowRun run =
                runRepo.findById(exec.getWorkflowRunId()).orElseThrow(() -> new NotFoundException("Run not found"));
        authService.checkOrgAccess("workflow_run", run.getId());
        return executionLogRepo.findByNodeExecutionIdOrderByTimestampAsc(nodeExecId).stream()
                .map(log -> new ExecutionLogResponse(
                        log.getId(), log.getLevel().name(), log.getMessage(), log.getTimestamp()))
                .toList();
    }

    public List<RunPullRequestResponse> getPullRequests(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);
        return runPullRequestService.getPullRequests(runId);
    }

    /**
     * Returns review history for a run, sourced from node_execution records
     * that have a loop_group set (i.e., nodes that participated in review loops).
     */
    public List<ReviewHistoryResponse> getReviewHistory(UUID runId, String loopGroup) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);
        return getReviewHistoryInternal(runId, loopGroup);
    }

    /**
     * Package-private method for review history — no org check.
     * Called from InternalRunService (same package).
     *
     * <p>A Story Design run's loop history spans all of its Story's runs, so a revision's reviewer
     * sees why earlier versions were decided and does not silently reverse them.
     */
    List<ReviewHistoryResponse> getReviewHistoryInternal(UUID runId, String loopGroup) {
        List<NodeExecution> reviews;
        if (loopGroup != null && !loopGroup.isBlank()) {
            UUID storyId = runRepo.findById(runId).map(WorkflowRun::getStoryId).orElse(null);
            reviews = storyId != null
                    ? execRepo.findStoryLoopHistory(storyId, loopGroup)
                    : execRepo.findByWorkflowRunIdAndLoopGroupOrderByIterationAsc(runId, loopGroup);
        } else {
            reviews = execRepo.findByWorkflowRunIdAndLoopGroupIsNotNullOrderByCompletedAtAsc(runId);
        }

        return reviews.stream()
                .map(e -> new ReviewHistoryResponse(
                        e.getId(),
                        e.getLoopGroup(),
                        e.getIteration(),
                        e.getReviewerType() != null ? e.getReviewerType().name() : null,
                        e.getDecision(),
                        e.getResult(),
                        e.getStatus() != null ? e.getStatus().name() : null,
                        e.getArtifactRefs(),
                        e.getLabel(),
                        e.getCompletedAt()))
                .toList();
    }

    Map<String, Object> mergeInputs(GraphTemplate template, Map<String, Object> userInputs) {
        Map<String, Object> merged = new HashMap<>();

        // Layer 1: apply schema defaults
        try {
            JsonNode schema = objectMapper.readTree(template.getInputSchema());
            for (JsonNode field : schema) {
                String name = field.get("name").asText();
                if (field.has("default") && !field.get("default").isNull()) {
                    merged.put(name, jsonNodeToObject(field.get("default")));
                }
            }
        } catch (Exception e) {
            // invalid schema — skip defaults
        }

        // Layer 2: apply user inputs (highest priority)
        if (userInputs != null) {
            merged.putAll(userInputs);
        }

        return merged;
    }

    Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble() || node.isFloat()) return node.doubleValue();
        if (node.isTextual()) return node.textValue();
        try {
            return objectMapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            return node.toString();
        }
    }

    void validateInputs(String inputSchemaJson, Map<String, Object> inputs) {
        try {
            JsonNode schema = objectMapper.readTree(inputSchemaJson);
            Map<String, Object> safeInputs = inputs != null ? inputs : Map.of();
            List<String> errors = new ArrayList<>();
            for (JsonNode field : schema) {
                String name = field.get("name").asText();
                String type = field.has("type") ? field.get("type").asText() : "string";
                boolean required =
                        field.has("required") && field.get("required").asBoolean();
                boolean hasDefault =
                        field.has("default") && !field.get("default").isNull();
                Object userValue = safeInputs.get(name);
                boolean userProvided =
                        userValue != null && !userValue.toString().isBlank();
                boolean explicitlyBlank =
                        userValue != null && userValue.toString().isBlank();
                if (required && !userProvided && (explicitlyBlank || !hasDefault)) {
                    errors.add("missing required input: " + name);
                }
                if ("git_repo".equals(type) && userProvided) {
                    try {
                        UUID repoId = UUID.fromString(userValue.toString());
                        if (!gitRepoRepo.existsById(repoId)) {
                            errors.add("git repo not found: " + repoId);
                        }
                    } catch (IllegalArgumentException e) {
                        errors.add("invalid git_repo_id format: " + userValue);
                    }
                }
                // Validate software_project_id type: UUID, exists, same org, resolves to >=1 repo
                if ("software_project_id".equals(type) && userProvided) {
                    try {
                        UUID projectId = UUID.fromString(userValue.toString());
                        SoftwareProject project =
                                softwareProjectRepo.findById(projectId).orElse(null);
                        if (project == null) {
                            errors.add("software project not found: " + projectId);
                        } else {
                            authService.checkOrgAccess("software_project", project.getId());
                            if (project instanceof com.choruskube.core.model.RepoGroup
                                    && repoGroupMemberRepo.countByRepoGroupId(projectId) == 0) {
                                errors.add("software project has no repositories: " + projectId);
                            }
                        }
                    } catch (IllegalArgumentException e) {
                        errors.add("invalid software_project_id format: " + userValue);
                    }
                }
            }
            if (!errors.isEmpty()) {
                throw new ValidationException(errors);
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            // empty or invalid schema — skip validation
        }
    }

    private WorkflowRun findRunOrThrow(UUID id) {
        return runRepo.findById(id).orElseThrow(() -> new NotFoundException("Workflow run not found: " + id));
    }

    Map<String, Object> buildWorkflowParams(WorkflowRun run, RunPlacement placement) {
        Map<String, Object> params = new HashMap<>();
        params.put("RunID", run.getId().toString());
        params.put("GraphVersion", run.getGraphVersion());

        // Resolve org slug for object storage path isolation
        params.put("OrgSlug", storagePrefixResolver.storagePrefixForRun(run.getId()));

        String inputRefs = run.getInputArtifactRefs();
        if (inputRefs != null && !inputRefs.isBlank() && !inputRefs.equals("{}")) {
            params.put("RunInputArtifactRefs", inputRefs);
        }

        params.put("WorkerTaskQueue", placement.taskQueue());

        return params;
    }

    /**
     * Builds the run's Task summary directly from {@code run.getTaskId()} — no
     * reverse lookup, since {@code task_id} is a forward FK on {@code workflow_run} itself. Also
     * walks {@code task.story_id -> story.epic_id} to surface the parent Story/Epic identity;
     * either level is independently nullable rather than failing the whole summary if a Story or
     * Epic no longer resolves.
     */
    private @Nullable RunTaskSummary buildTaskSummary(WorkflowRun run) {
        if (run.getTaskId() == null) {
            return null;
        }
        return taskRepo.findById(run.getTaskId())
                .map(task -> {
                    SoftwareProjectRef projectRef = softwareProjectRepo
                            .findById(task.getSoftwareProjectId())
                            .map(this::toSoftwareProjectRef)
                            .orElse(null);
                    Story story = storyRepo.findById(task.getStoryId()).orElse(null);
                    Epic epic =
                            story != null ? epicRepo.findById(story.getEpicId()).orElse(null) : null;
                    return new RunTaskSummary(
                            task.getId(),
                            task.getTitle(),
                            task.getStatus().name(),
                            projectRef,
                            story != null ? story.getId() : null,
                            story != null ? story.getTitle() : null,
                            epic != null ? epic.getId() : null,
                            epic != null ? epic.getTitle() : null);
                })
                .orElse(null);
    }

    private RunResponse toResponse(WorkflowRun run, List<NodeExecution> execs, @Nullable RunTaskSummary taskSummary) {
        GraphTemplate template =
                graphTemplateRepo.findById(run.getGraphTemplateId()).orElse(null);
        String templateName = template != null ? template.getName() : "Unknown";
        String promptText = extractPromptText(template, run.getInputs());

        JsonNode snapshotJson = null;
        try {
            String snapshot = snapshotBuilder.buildSnapshotForRun(run);
            snapshotJson = objectMapper.readTree(snapshot);
        } catch (Exception e) {
            logger.warn("Failed to build graph snapshot for run {}: {}", run.getId(), e.getMessage());
        }

        // Resolved once per run (not per execution) — a cheap linear scan of the snapshot's nodes
        // array, reused below to scope escalation resolution to the Supervisor's own gate
        // execution. `snapshotJson` is null when the snapshot build above failed; findRoutingHub
        // tolerates that and returns null.
        JsonNode routingHub = decisionOptionsResolver.findRoutingHub(snapshotJson);

        List<NodeExecutionResponse> execResponses = execs.stream()
                .map(e -> {
                    UUID[] edges = e.getTraversedEdgeIds();
                    List<UUID> edgeList = edges == null ? null : java.util.Arrays.asList(edges);
                    boolean isGateStatus = e.getStatus() == NodeExecutionStatus.awaiting_human;
                    List<ResolvedArtifactGroup> requiredArtifacts = isGateStatus
                            ? artifactResolutionService.resolveRequiredArtifacts(e.getTemplateNodeId(), run.getId())
                            : null;
                    // Mirrors PendingGateService's resolution of the same artifact, reusing the
                    // requiredArtifacts already computed above — so the Run Detail page's gate
                    // surface (HumanGatePanel via DetailPanel) can render the same editable
                    // breakdown the Approvals dashboard does, instead of silently having none.
                    RoadmapCandidatesDocument candidateBreakdown = requiredArtifacts != null
                            ? roadmapCandidatesArtifactResolver.resolve(run.getId(), requiredArtifacts)
                            : null;
                    // Mirrors PendingGateService's resolution of the same context — the Supervisor
                    // has no inbound edges, so predecessorOutputs carries nothing for it; this is
                    // the Run Detail page's replacement, exactly as it is for the Approvals
                    // dashboard. Scoped to the routing-hub node so an ordinary gate never gets an
                    // unrelated escalation banner, and to gate-status executions (like
                    // requiredArtifacts above) so the resolution cost isn't paid for every
                    // execution in the run.
                    boolean isRoutingHubExec = isGateStatus
                            && routingHub != null
                            && routingHub
                                    .get("template_node_id")
                                    .asText()
                                    .equals(e.getTemplateNodeId().toString());
                    EscalationContext escalation =
                            isRoutingHubExec ? escalationContextResolver.resolve(run.getId()) : null;
                    return new NodeExecutionResponse(
                            e.getId(),
                            e.getTemplateNodeId(),
                            e.getStatus().name(),
                            e.getResult(),
                            e.getDecision(),
                            e.getPodName(),
                            e.getIteration(),
                            e.getStartedAt(),
                            e.getCompletedAt(),
                            e.getErrorMessage(),
                            e.getGraphVersion(),
                            e.getArtifactRefs(),
                            e.getLabel(),
                            e.getLoopGroup(),
                            e.getReviewerType() != null ? e.getReviewerType().name() : null,
                            edgeList,
                            requiredArtifacts,
                            candidateBreakdown,
                            escalation);
                })
                .toList();
        List<RunPullRequestResponse> pullRequests;
        try {
            pullRequests = runPullRequestService.getPullRequests(run.getId());
        } catch (Exception e) {
            logger.warn("Failed to fetch pull requests for run {}: {}", run.getId(), e.getMessage());
            pullRequests = List.of();
        }

        // Resolve the top-level softwareProject reference.
        // Task wins when present (even if its project was deleted → null is correct).
        // Fall back to inputs.software_project_id only when no Task is linked.
        SoftwareProjectRef softwareProjectRef;
        if (taskSummary != null) {
            softwareProjectRef = taskSummary.softwareProject();
        } else {
            UUID fromInputs = extractSoftwareProjectIdFromInputs(run.getInputs());
            softwareProjectRef = (fromInputs == null)
                    ? null
                    : softwareProjectRepo
                            .findById(fromInputs)
                            .map(this::toSoftwareProjectRef)
                            .orElse(null);
        }

        return new RunResponse(
                run.getId(),
                run.getGraphTemplateId(),
                templateName,
                run.getName(),
                run.getStatus().name(),
                run.getExternalRunId(),
                run.getGraphVersion(),
                snapshotJson,
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCreatedAt(),
                execResponses,
                pullRequests,
                run.getInputArtifactRefs(),
                promptText,
                taskSummary,
                run.getAutopilotId(),
                softwareProjectRef);
    }

    private @Nullable String extractPromptText(@Nullable GraphTemplate template, @Nullable String inputsJson) {
        if (template == null || template.getPromptInputKey() == null || inputsJson == null || inputsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(inputsJson).get(template.getPromptInputKey());
            if (node == null || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.asText() : node.toString();
        } catch (Exception e) {
            logger.warn(
                    "Failed to extract promptText for run inputs (template {}): {}", template.getId(), e.getMessage());
            return null;
        }
    }

    private SoftwareProjectRef toSoftwareProjectRef(SoftwareProject sp) {
        String type = (sp instanceof RepoGroup) ? "repo_group" : "git_repo";
        return new SoftwareProjectRef(sp.getId(), type, sp.getName());
    }

    private @Nullable UUID extractSoftwareProjectIdFromInputs(@Nullable String inputsJson) {
        if (inputsJson == null || inputsJson.isBlank() || "{}".equals(inputsJson)) return null;
        try {
            JsonNode node = objectMapper.readTree(inputsJson).get("software_project_id");
            if (node == null || node.isNull() || !node.isTextual()) return null;
            return UUID.fromString(node.asText());
        } catch (Exception e) {
            return null;
        }
    }

    // Temporal signal payload — not a REST DTO; not exposed outside RunService.
    private record HumanDecisionPayload(
            @JsonProperty("nodeExecutionId") String nodeExecutionId,
            @JsonProperty("decision") String decision,
            @JsonProperty("feedback") String feedback,
            @JsonProperty("attachmentRefs") String attachmentRefs,

            @JsonProperty("approvalId") @JsonInclude(JsonInclude.Include.NON_NULL)
            String approvalId) {}
}
