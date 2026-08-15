package com.choruskube.core.service;

import com.choruskube.core.credential.CredentialPreflightChecker;
import com.choruskube.core.dto.*;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.observability.UsageSink;
import com.choruskube.core.repository.*;
import com.choruskube.core.scope.ScopeProvider;
import com.choruskube.core.specification.LikePatterns;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
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
    static final String RUN_NAME_TRUNCATION_MARKER = "…";

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository execRepo;
    private final TemplateEdgeRepository edgeRepo;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final WorkflowClient workflowClient;
    private final GraphTemplateRepository graphTemplateRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final GraphValidationService validationService;
    private final ExecutionLogRepository executionLogRepo;
    private final ObjectMapper objectMapper;
    private final RunEventPublisher eventPublisher;
    private final GitRepoRepository gitRepoRepo;
    private final WorkloadService workloadService;
    private final AuthorizationService authService;
    private final Optional<QuotaChecker> quotaService;
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

    /** Config key on a gate's {@code config_overrides} that opts it into materialization (Decision 3). */
    static final String MATERIALIZE_CONFIG_KEY = "materialize";

    static final String MATERIALIZE_ROADMAP_CANDIDATES = "roadmap_candidates";

    @Value("${temporal.task-queue}")
    private String taskQueue;

    public RunService(
            WorkflowRunRepository runRepo,
            NodeExecutionRepository execRepo,
            TemplateEdgeRepository edgeRepo,
            GraphSnapshotBuilder snapshotBuilder,
            WorkflowClient workflowClient,
            GraphTemplateRepository graphTemplateRepo,
            TemplateNodeRepository templateNodeRepo,
            GraphValidationService validationService,
            ExecutionLogRepository executionLogRepo,
            ObjectMapper objectMapper,
            RunEventPublisher eventPublisher,
            GitRepoRepository gitRepoRepo,
            WorkloadService workloadService,
            AuthorizationService authService,
            Optional<QuotaChecker> quotaService,
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
            EscalationContextResolver escalationContextResolver) {
        this.runRepo = runRepo;
        this.execRepo = execRepo;
        this.edgeRepo = edgeRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.workflowClient = workflowClient;
        this.graphTemplateRepo = graphTemplateRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.validationService = validationService;
        this.executionLogRepo = executionLogRepo;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.gitRepoRepo = gitRepoRepo;
        this.workloadService = workloadService;
        this.authService = authService;
        this.quotaService = quotaService;
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
    }

    @Transactional
    public RunResponse startRun(CreateRunRequest request) {
        GraphTemplate template = graphTemplateRepo
                .findById(request.graphTemplateId())
                .orElseThrow(() -> new NotFoundException("Template not found"));

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
        run = runRepo.save(run);

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .build();

        final WorkflowRun committedRun = run;
        final Map<String, Object> finalWorkflowParams = buildWorkflowParams(run);
        final WorkflowStub finalWorkflow = workflowClient.newUntypedWorkflowStub("DAGExecutorWorkflow", options);

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

        // Batch-fetch all referenced template names in a single query to avoid N+1
        Set<UUID> templateIds =
                page.stream().map(WorkflowRun::getGraphTemplateId).collect(Collectors.toSet());
        Map<UUID, String> templateNames = graphTemplateRepo.findAllById(templateIds).stream()
                .collect(Collectors.toMap(GraphTemplate::getId, GraphTemplate::getName));

        // Batch-fetch task info for the page (avoids N+1). task_id is a direct FK on WorkflowRun
        // (Decision 1), so no reverse lookup is needed — just batch-load the referenced Task rows.
        Set<UUID> taskIds = page.stream()
                .map(WorkflowRun::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Task> taskById = taskIds.isEmpty()
                ? Map.of()
                : taskRepo.findAllById(taskIds).stream().collect(Collectors.toMap(Task::getId, t -> t));

        // Resolve the project ID for each run: task wins; fall back to inputs.
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

        // Build the per-run SoftwareProjectRef map
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
        // Delete K8s jobs for running nodes immediately to avoid orphaned pods
        // while the run is paused. After the pod is gone, Temporal's heartbeat
        // timeout (≤15 min) will fire, transition the node to failed, and move
        // the run into awaiting_retry. The user must explicitly retry nodes on resume.
        for (NodeExecution exec : execRepo.findByWorkflowRunId(id)) {
            if (exec.getStatus() == NodeExecutionStatus.running) {
                cleanupWorkloadQuietly(exec.getId());
            }
        }
        auditSink.record(AuditSink.RUN_PAUSED, "workflow_run", id, null);
    }

    public void resumeRun(UUID id) {
        WorkflowRun run = findRunOrThrow(id);
        authService.checkOrgAccess("workflow_run", id);
        rejectIfTerminal(run, "resume");
        signalWorkflowOrThrow(run, "resume");
        auditSink.record(AuditSink.RUN_RESUMED, "workflow_run", id, null);
    }

    public void cancelRun(UUID id) {
        WorkflowRun run = findRunOrThrow(id);
        authService.checkOrgAccess("workflow_run", id);
        rejectIfTerminal(run, "cancel");
        signalWorkflow(run, "cancel");
        run.setStatus(WorkflowRunStatus.cancelled);
        runRepo.save(run);
        auditSink.record(AuditSink.RUN_CANCELLED, "workflow_run", id, null);

        // Mark any non-terminal node executions as skipped so they don't
        // linger on the approvals page. This is necessary because the
        // Temporal workflow may have already completed or been cancelled
        // externally, in which case the orchestrator's cleanup never runs.
        Set<NodeExecutionStatus> activeStatuses = Set.of(
                NodeExecutionStatus.pending,
                NodeExecutionStatus.running,
                NodeExecutionStatus.awaiting_human,
                NodeExecutionStatus.live_chat);
        for (NodeExecution exec : execRepo.findByWorkflowRunId(id)) {
            if (activeStatuses.contains(exec.getStatus())) {
                // Hard-kill any live workload before marking skipped. Fire-and-forget:
                // a missing or already-gone workload is fine — the Temporal cancel
                // signal would otherwise leave the pod running until its activity
                // timeout eventually fires.
                if (exec.getStatus() == NodeExecutionStatus.running) {
                    cleanupWorkloadQuietly(exec.getId());
                }
                exec.setStatus(NodeExecutionStatus.skipped);
                execRepo.save(exec);
                eventPublisher.publishNodeStatusChanged(id, exec.getId(), "skipped");
            }
        }
    }

    public void retryNode(UUID runId, UUID nodeExecId) {
        WorkflowRun run = findRunOrThrow(runId);
        authService.checkOrgAccess("workflow_run", runId);

        // Allow retry only when workflow is waiting for it
        if (run.getStatus() != WorkflowRunStatus.awaiting_retry) {
            throw new ValidationException(
                    List.of("Cannot retry node: run status is " + run.getStatus() + ", expected awaiting_retry"));
        }

        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        if (exec.getStatus() != NodeExecutionStatus.failed) {
            throw new ValidationException(
                    List.of("Cannot retry node: node status is " + exec.getStatus() + ", expected failed"));
        }

        // Hard-kill any lingering workload for this execution before signaling retry.
        // Covers the zombie-pod case where Temporal timed out the activity but the
        // agent pod is still running — retrying without terminating first would
        // leave two agents racing on the same working branch.
        cleanupWorkloadQuietly(nodeExecId);

        // Signal the Temporal workflow with the template node ID
        WorkflowStub stub = workflowClient.newUntypedWorkflowStub(run.getExternalRunId());
        Map<String, String> payload =
                Map.of("templateNodeId", exec.getTemplateNodeId().toString());
        stub.signal("retry-node", payload);
    }

    private void cleanupWorkloadQuietly(UUID nodeExecId) {
        try {
            workloadService.cleanupWorkload(nodeExecId);
        } catch (Exception e) {
            logger.warn("cleanupWorkload failed for {} (likely already gone): {}", nodeExecId, e.getMessage());
        }
    }

    /**
     * Send a signal to the Temporal workflow. Returns true if the signal was sent,
     * false if the workflow has already completed (NOT_FOUND).
     * Other Temporal errors (connectivity, etc.) propagate as-is.
     */
    private boolean signalWorkflow(WorkflowRun run, String signalName) {
        try {
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(run.getExternalRunId());
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

    public void signalHumanDecision(UUID runId, UUID nodeExecId, SignalRequest request) {
        WorkflowRun run = findRunOrThrow(runId);
        authService.checkOrgAccess("workflow_run", runId);
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        // Verify the node execution belongs to the requested run before claiming it.
        NodeExecutionUtil.requireInRun(exec, runId);

        // Atomically claim the node before doing anything else. Two concurrent/duplicate
        // submissions (double-click, two open tabs, a client retry) would otherwise both pass
        // validation below and both independently materialize the Roadmap Provisioner candidate
        // breakdown, silently creating duplicate Epic/Story/Task rows with nothing surfaced to the
        // reviewer. This is a single atomic UPDATE ... WHERE status = awaiting_human — only one
        // concurrent caller can win it; the other sees 0 rows affected and is rejected outright.
        int claimed = nodeExecutionClaimService.compareAndSetStatus(
                nodeExecId, NodeExecutionStatus.awaiting_human, NodeExecutionStatus.running);
        if (claimed == 0) {
            throw new ConflictException("Cannot signal decision: node execution is not awaiting a human decision "
                    + "(already decided, or a concurrent request already claimed it)");
        }

        // From here on, any failure (bad decision string, materialization error, Temporal signal
        // failure) must release the claim back to awaiting_human so the request stays retryable —
        // e.g. a typo'd decision string is a routine, expected error today and must not
        // permanently strand the gate. This only reopens a (much narrower, pre-existing) window
        // where a retry after materialization already succeeded but the signal itself then failed
        // could re-materialize; the case this guard exists for — two concurrent/duplicate
        // submissions racing each other — is fully closed by the claim above.
        try {
            JsonNode snapshot = readSnapshot(run);

            // Validate decision against the union of edge conditions and terminal_decisions
            String validatedDecision =
                    validateDecisionAgainstEdges(snapshot, exec.getTemplateNodeId(), request.decision());

            // Assemble the full result for the signal — includes any existing content
            // (e.g. live chat transcript) plus human feedback. The orchestrator will
            // write this as the node's result via UpdateNodeExecutionStatus, keeping
            // a single authoritative write path for result.
            String assembledResult = exec.getResult();
            if (request.feedback() != null && !request.feedback().isBlank()) {
                if (assembledResult != null && !assembledResult.isBlank()) {
                    assembledResult = "## Chat Transcript\n\n"
                            + assembledResult
                            + "\n\n## Reviewer Feedback\n\n"
                            + request.feedback();
                } else {
                    assembledResult = "## Reviewer Feedback\n\n" + request.feedback();
                }
            }

            // Deterministic materialization (Decision 3): on approval of a gate configured for it,
            // turn the reviewed (possibly reviewer-edited) Roadmap Provisioner candidate breakdown
            // directly into Epic/Story/Task rows — through the same write path a human uses — in
            // the same request that handles the decision signal, rather than via a second AI
            // agent.
            if ("approved".equalsIgnoreCase(validatedDecision)
                    && isMaterializeNode(snapshot, exec.getTemplateNodeId())) {
                // A present-but-empty editedCandidates means the reviewer deliberately cleared the
                // breakdown (e.g. rejecting every candidate while still approving the gate for some
                // other reason) and must NOT fall back to the original analyzer artifact — only a
                // genuinely absent field (no edits submitted at all) does that.
                List<CandidateEpicProposal> source = request.editedCandidates() != null
                        ? request.editedCandidates()
                        : roadmapCandidatesArtifactResolver.resolve(runId, exec.getTemplateNodeId());
                String materializeNote;
                if (source != null) {
                    MaterializationSummary summary = roadmapCandidateMaterializer.materialize(runId, source);
                    materializeNote = "Materialized " + summary.materializedCount() + " Epics ("
                            + summary.skippedCount() + " skipped)";
                } else {
                    // The artifact resolver degrades to null (never throws) when the candidate
                    // breakdown is missing or malformed — surface that instead of silently
                    // approving the gate with no materialization and no trace of why.
                    materializeNote = "Materialization skipped: no candidate breakdown was found for this run";
                }
                assembledResult = (assembledResult != null && !assembledResult.isBlank())
                        ? assembledResult + "\n\n" + materializeNote
                        : materializeNote;
            }

            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(run.getExternalRunId());
            HumanDecisionPayload payload = new HumanDecisionPayload(
                    nodeExecId.toString(),
                    validatedDecision,
                    assembledResult != null ? assembledResult : "",
                    request.attachmentRefs() != null ? request.attachmentRefs() : "{}");
            stub.signal("human-decision-" + nodeExecId.toString(), payload);

            eventPublisher.publishNodeStatusChanged(runId, nodeExecId, "signaled");
        } catch (RuntimeException e) {
            nodeExecutionClaimService.compareAndSetStatus(
                    nodeExecId, NodeExecutionStatus.running, NodeExecutionStatus.awaiting_human);
            throw e;
        }
    }

    private JsonNode readSnapshot(WorkflowRun run) {
        try {
            return objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build graph snapshot", e);
        }
    }

    /**
     * Validates a decision against the union of a node's outgoing edge conditions and its
     * configured {@code terminal_decisions} (Decision 2), via {@link DecisionOptionsResolver}.
     * Returns the canonical (case-matched) decision string.
     */
    private String validateDecisionAgainstEdges(JsonNode snapshot, UUID templateNodeId, String decision) {
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

    /**
     * Whether this node is configured to materialize its approved candidate breakdown (Decision
     * 3) — i.e. its {@code config_overrides.materialize} equals {@code "roadmap_candidates"}.
     */
    private boolean isMaterializeNode(JsonNode snapshot, UUID templateNodeId) {
        JsonNode nodeConfigOverrides =
                decisionOptionsResolver.findNodeConfigOverrides(snapshot.get("nodes"), templateNodeId);
        return nodeConfigOverrides != null
                && nodeConfigOverrides.has(MATERIALIZE_CONFIG_KEY)
                && MATERIALIZE_ROADMAP_CANDIDATES.equals(
                        nodeConfigOverrides.get(MATERIALIZE_CONFIG_KEY).asText());
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

    /**
     * Returns pull requests for a run, enforcing org access check.
     */
    public List<RunPullRequestResponse> getPullRequests(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        authService.checkOrgAccess("workflow_run", runId);
        return runPullRequestService.getPullRequests(runId);
    }

    /**
     * Returns review history for a run, sourced from node_execution records
     * that have a loop_group set (i.e., nodes that participated in review loops).
     * Enforces org access check.
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
     */
    List<ReviewHistoryResponse> getReviewHistoryInternal(UUID runId, String loopGroup) {
        List<NodeExecution> reviews;
        if (loopGroup != null && !loopGroup.isBlank()) {
            reviews = execRepo.findByWorkflowRunIdAndLoopGroupOrderByIterationAsc(runId, loopGroup);
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

    /**
     * Converts a JsonNode to a plain Java Object suitable for Map<String, Object>.
     */
    Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isDouble() || node.isFloat()) return node.doubleValue();
        if (node.isTextual()) return node.textValue();
        // For arrays and objects, use treeToValue
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
                // Validate git_repo type: must be valid UUID referencing existing entity
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

    Map<String, Object> buildWorkflowParams(WorkflowRun run) {
        Map<String, Object> params = new HashMap<>();
        params.put("RunID", run.getId().toString());
        params.put("GraphVersion", run.getGraphVersion());

        // Resolve org slug for object storage path isolation
        params.put("OrgSlug", storagePrefixResolver.storagePrefixForRun(run.getId()));

        // Propagate run-level input attachments to the orchestrator workflow
        String inputRefs = run.getInputArtifactRefs();
        if (inputRefs != null && !inputRefs.isBlank() && !inputRefs.equals("{}")) {
            params.put("RunInputArtifactRefs", inputRefs);
        }

        return params;
    }

    /**
     * Builds the run's Task summary directly from {@code run.getTaskId()} (Decision 1) — no
     * reverse lookup, since {@code task_id} is a forward FK on {@code workflow_run} itself. Also
     * walks {@code task.story_id -> story.epic_id} to surface the parent Story/Epic identity;
     * either level is independently nullable rather than failing the whole summary if a Story or
     * Epic no longer resolves (Caveat 1).
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

        // Build snapshot on-demand from template tables
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
                    boolean isGateStatus = e.getStatus() == NodeExecutionStatus.awaiting_human
                            || e.getStatus() == NodeExecutionStatus.live_chat;
                    List<ResolvedArtifactGroup> requiredArtifacts = isGateStatus
                            ? artifactResolutionService.resolveRequiredArtifacts(e.getTemplateNodeId(), run.getId())
                            : null;
                    // Mirrors PendingGateService's resolution of the same artifact, reusing the
                    // requiredArtifacts already computed above — so the Run Detail page's gate
                    // surface (HumanGatePanel via DetailPanel) can render the same editable
                    // breakdown the Approvals dashboard does, instead of silently having none.
                    List<CandidateEpicProposal> candidateBreakdown = requiredArtifacts != null
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

    /**
     * Converts a {@link SoftwareProject} entity to its DTO reference, discriminating
     * between {@link RepoGroup} ("repo_group") and other implementations ("git_repo").
     * Mirrors {@code DefaultEpicService.toProjectRef()}.
     */
    private SoftwareProjectRef toSoftwareProjectRef(SoftwareProject sp) {
        String type = (sp instanceof RepoGroup) ? "repo_group" : "git_repo";
        return new SoftwareProjectRef(sp.getId(), type, sp.getName());
    }

    /**
     * Parses {@code inputs.software_project_id} from the run's JSONB inputs string.
     * Returns {@code null} when the field is absent, non-textual, or not a valid UUID.
     */
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
            @JsonProperty("attachmentRefs") String attachmentRefs) {}
}
