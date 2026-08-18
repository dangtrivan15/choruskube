package com.choruskube.core.service;

import com.choruskube.core.dto.*;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.*;
import com.choruskube.core.model.enums.*;
import com.choruskube.core.observability.UsageSink;
import com.choruskube.core.repository.*;
import com.choruskube.core.util.NodeExecutionUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.*;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InternalRunService {

    private static final Logger log = LoggerFactory.getLogger(InternalRunService.class);

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository execRepo;
    private final ExecutionLogRepository logRepo;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final RunEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final EpicService epicService;
    private final StoryService storyService;
    private final TaskService taskService;
    private final RunService runService;
    private final Optional<QuotaChecker> quotaService;
    private final UsageSink usageSink;
    private final GitRepoRepository gitRepoRepo;
    private final RunPullRequestService runPullRequestService;
    private final GraphTemplateRepository graphTemplateRepo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final NodeDefinitionRepository nodeDefinitionRepo;
    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;
    private final EpicRepository epicRepo;
    private final RoadmapGraphService roadmapGraphService;
    private final DecisionOptionsResolver decisionOptionsResolver;
    private final WorkItemDependencyRepository dependencyRepo;
    private final ArtifactService artifactService;

    @Value("${artifact.enforcement.mode:warn}")
    private String artifactEnforcementMode;

    public InternalRunService(
            WorkflowRunRepository runRepo,
            NodeExecutionRepository execRepo,
            ExecutionLogRepository logRepo,
            GraphSnapshotBuilder snapshotBuilder,
            RunEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            EpicService epicService,
            StoryService storyService,
            TaskService taskService,
            RunService runService,
            Optional<QuotaChecker> quotaService,
            UsageSink usageSink,
            GitRepoRepository gitRepoRepo,
            RunPullRequestService runPullRequestService,
            GraphTemplateRepository graphTemplateRepo,
            SoftwareProjectRepository softwareProjectRepo,
            TemplateNodeRepository templateNodeRepo,
            NodeDefinitionRepository nodeDefinitionRepo,
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            EpicRepository epicRepo,
            RoadmapGraphService roadmapGraphService,
            DecisionOptionsResolver decisionOptionsResolver,
            WorkItemDependencyRepository dependencyRepo,
            ArtifactService artifactService) {
        this.runRepo = runRepo;
        this.execRepo = execRepo;
        this.logRepo = logRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.epicService = epicService;
        this.storyService = storyService;
        this.taskService = taskService;
        this.runService = runService;
        this.quotaService = quotaService;
        this.usageSink = usageSink;
        this.gitRepoRepo = gitRepoRepo;
        this.runPullRequestService = runPullRequestService;
        this.graphTemplateRepo = graphTemplateRepo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.nodeDefinitionRepo = nodeDefinitionRepo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.epicRepo = epicRepo;
        this.roadmapGraphService = roadmapGraphService;
        this.decisionOptionsResolver = decisionOptionsResolver;
        this.dependencyRepo = dependencyRepo;
        this.artifactService = artifactService;
    }

    public NodeExecutionResponse createNodeExecution(UUID runId, InternalCreateNodeExecutionRequest req) {
        // Validate the run exists before exec creation; the quota check resolves the owning org from
        // the run id (the agent path has no TenantContext).
        runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));

        quotaService.ifPresent(svc -> svc.checkNodeExecutionQuota(runId)); // throws QuotaExceededException (429)

        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(req.templateNodeId());
        exec.setGraphVersion(req.graphVersion());
        exec.setIteration(req.iteration() > 0 ? req.iteration() : 1);
        exec.setLabel(req.label());
        exec = execRepo.save(exec);

        usageSink.record(UsageSink.EXECUTION_STARTED, "node_execution", exec.getId(), null);

        eventPublisher.publishNodeStatusChanged(
                runId, exec.getId(), exec.getStatus().name());
        return toNodeExecResponse(exec);
    }

    public NodeExecutionResponse updateNodeExecutionStatus(
            UUID runId, UUID nodeExecId, InternalUpdateNodeExecutionRequest req) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        // Defense-in-depth: reject completed status with empty result only on rejection,
        // where the AI node must provide a reason. Human nodes approved without feedback
        // legitimately have no result text.
        if ("completed".equals(req.status())
                && "rejected".equals(exec.getDecision())
                && (req.result() == null || req.result().isBlank())) {
            throw new IllegalArgumentException("Cannot mark rejected node completed with empty result");
        }

        exec.setStatus(NodeExecutionStatus.valueOf(req.status()));
        if (req.result() != null) exec.setResult(req.result());
        if (req.artifactRefs() != null) exec.setArtifactRefs(req.artifactRefs());
        if (req.podName() != null) exec.setPodName(req.podName());
        if (req.jobSecretHash() != null) exec.setJobSecretHash(req.jobSecretHash());
        if (req.errorMessage() != null) exec.setErrorMessage(req.errorMessage());

        String status = req.status();
        Instant now = Instant.now();
        if ("running".equals(status) && exec.getStartedAt() == null) exec.setStartedAt(now);
        if ("completed".equals(status) || "failed".equals(status)) exec.setCompletedAt(now);

        // Auto-set no_decision for nodes without conditional edges
        if ("completed".equals(status) && exec.getDecision() == null) {
            if (!hasConditionalEdges(runId, exec.getTemplateNodeId())) {
                exec.setDecision("no_decision");
            }
        }

        if ("completed".equals(status)) {
            enforceOutputSpec(exec, exec.getArtifactRefs());
        }

        exec = execRepo.save(exec);

        // Validates the run exists; org no longer needed (feeds published org-free, re-scoped downstream).
        runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        eventPublisher.publishNodeStatusChanged(runId, nodeExecId, status);
        return toNodeExecResponse(exec);
    }

    private void enforceOutputSpec(NodeExecution exec, String artifactRefs) {
        // Platform contract: an `escalate` decision must be accompanied by escalation.md, so the
        // Supervisor's reviewer is never asked to act on an unexplained escalation. Enforced
        // unconditionally — unlike the legacy static required-file check below, this rule is new
        // and has no pre-existing declarations whose lenient behaviour must be preserved, so it
        // does not consult artifact.enforcement.mode.
        if (DecisionOptionsResolver.ESCALATE_DECISION.equalsIgnoreCase(exec.getDecision())) {
            List<String> names;
            try {
                // Pass the in-hand artifactRefs (already applied to `exec` above, in this same
                // method, ahead of the execRepo.save below) rather than looking the execution back
                // up by id — see ArtifactService.listArtifactNamesInternal's javadoc for why a
                // fresh repository read here would race the callback that is persisting it.
                names = artifactService.listArtifactNamesInternal(artifactRefs);
            } catch (RuntimeException e) {
                // Fail closed, deliberately: this is a security-relevant gate — an unverified
                // escalation must never reach the Supervisor's human reviewer — so a storage
                // outage must not be reported as "escalation.md is missing" (400, the wrong
                // diagnosis) and must not be swallowed into silently admitting the escalation
                // either. Surface it as a distinct status so the two failure modes stay
                // distinguishable to the caller.
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Could not verify escalation.md presence: object storage is unavailable",
                        e);
            }
            if (names.stream().noneMatch("escalation.md"::equals)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Node execution submitted decision 'escalate' without producing escalation.md");
            }
        }

        TemplateNode templateNode =
                templateNodeRepo.findById(exec.getTemplateNodeId()).orElse(null);
        if (templateNode == null) {
            return;
        }
        NodeDefinition nodeDefinition =
                nodeDefinitionRepo.findById(templateNode.getNodeDefinitionId()).orElse(null);
        if (nodeDefinition == null) {
            return;
        }
        String outputSpecJson = nodeDefinition.getOutputSpec();
        if (outputSpecJson == null || outputSpecJson.isBlank()) {
            return;
        }
        try {
            JsonNode outputSpec = objectMapper.readTree(outputSpecJson);
            JsonNode filesNode = outputSpec.path("files");
            if (!filesNode.isArray() || filesNode.isEmpty()) {
                return;
            }
            boolean hasRequiredFiles = false;
            for (JsonNode fileNode : filesNode) {
                if (fileNode.path("required").asBoolean(false)) {
                    hasRequiredFiles = true;
                    break;
                }
            }
            if (!hasRequiredFiles) {
                return;
            }
            boolean missingArtifacts;
            try {
                missingArtifacts = artifactRefs == null
                        || objectMapper.readTree(artifactRefs).isEmpty();
            } catch (Exception parseEx) {
                missingArtifacts = false; // malformed JSON—don't penalise
            }
            if (missingArtifacts) {
                if ("enforce".equals(artifactEnforcementMode)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Node execution completed without required output artifacts for node definition: "
                                    + nodeDefinition.getName());
                } else {
                    log.warn(
                            "Node execution {} completed without required output artifacts for node definition: {}",
                            exec.getId(),
                            nodeDefinition.getName());
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to enforce output spec for node execution {}: {}", exec.getId(), e.getMessage());
        }
    }

    public RunStatusResponse getRunStatus(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        return new RunStatusResponse(run.getStatus().name());
    }

    public void updateRunStatus(UUID runId, InternalUpdateRunStatusRequest req) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        run.setStatus(WorkflowRunStatus.valueOf(req.status()));

        Instant now = Instant.now();
        String status = req.status();
        if ("running".equals(status) && run.getStartedAt() == null) run.setStartedAt(now);
        if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
            run.setCompletedAt(now);
        }

        runRepo.save(run);
        eventPublisher.publishRunStatusChanged(runId, status);

        if ("completed".equals(status)) {
            usageSink.record(UsageSink.RUN_COMPLETED, "workflow_run", runId, null);
        } else if ("failed".equals(status)) {
            usageSink.record(UsageSink.RUN_FAILED, "workflow_run", runId, null);
        }
    }

    public void writeExecutionLog(UUID runId, UUID nodeExecId, InternalWriteLogRequest req) {
        ExecutionLog log = new ExecutionLog();
        log.setNodeExecutionId(nodeExecId);
        log.setLevel(LogLevel.valueOf(req.level().toLowerCase()));
        log.setMessage(req.message());
        logRepo.save(log);
        eventPublisher.publishNodeLogsUpdated(runId, nodeExecId);
    }

    /**
     * Writes review metadata to the node_execution record.
     * Decision is NOT set here — it's set by submitDecision (conditional nodes)
     * or auto-set to "no_decision" by updateNodeExecutionStatus (unconditional nodes).
     */
    public void writeReviewHistory(UUID runId, InternalWriteReviewHistoryRequest req) {
        NodeExecution exec = execRepo.findById(req.nodeExecutionId())
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + req.nodeExecutionId()));

        exec.setLoopGroup(req.loopGroup());
        exec.setReviewerType(ReviewerType.valueOf(req.reviewerType().toLowerCase()));
        if (req.artifactRefs() != null && !req.artifactRefs().isBlank()) {
            exec.setArtifactRefs(req.artifactRefs());
        }

        execRepo.save(exec);
    }

    public List<ReviewHistoryResponse> getReviewHistory(UUID runId, String loopGroup) {
        return runService.getReviewHistoryInternal(runId, loopGroup);
    }

    /**
     * Builds the graph snapshot on-demand from versioned template tables + workflow_run.inputs.
     */
    public JsonNode getGraphSnapshot(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        try {
            String snapshot = snapshotBuilder.buildSnapshotForRun(run);
            return objectMapper.readTree(snapshot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build graph snapshot", e);
        }
    }

    /**
     * Builds a projected graph snapshot containing only workflow-execution fields.
     * Infrastructure fields (image, secrets, namespace, docker config) are resolved
     * by the API server during workload creation, not exposed to the orchestrator.
     */
    public GraphRuntimeSnapshotResponse getGraphRuntimeSnapshot(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        try {
            String snapshotJson = snapshotBuilder.buildSnapshotForRun(run);
            JsonNode snapshot = objectMapper.readTree(snapshotJson);

            List<GraphRuntimeSnapshotResponse.RuntimeNode> nodes = new ArrayList<>();
            for (JsonNode n : snapshot.path("nodes")) {
                Map<String, Object> configOverrides = Map.of();
                if (n.has("config_overrides") && !n.get("config_overrides").isNull()) {
                    configOverrides = objectMapper.convertValue(
                            n.get("config_overrides"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                }
                nodes.add(new GraphRuntimeSnapshotResponse.RuntimeNode(
                        UUID.fromString(n.get("template_node_id").asText()),
                        n.path("label").asText(null),
                        n.path("executor_type").asText(null),
                        n.path("prompt_template").asText(null),
                        n.path("model").asText(null),
                        n.path("timeout_seconds").asInt(0),
                        configOverrides,
                        n.path("is_entrypoint").asBoolean(false),
                        n.has("output_spec") && !n.get("output_spec").isNull()
                                ? n.get("output_spec").toString()
                                : null));
            }

            List<GraphRuntimeSnapshotResponse.RuntimeEdge> edges = new ArrayList<>();
            for (JsonNode e : snapshot.path("edges")) {
                edges.add(new GraphRuntimeSnapshotResponse.RuntimeEdge(
                        UUID.fromString(e.get("template_edge_id").asText()),
                        UUID.fromString(e.get("source_node_id").asText()),
                        UUID.fromString(e.get("target_node_id").asText()),
                        e.path("condition").asText(null)));
            }

            Map<String, Object> inputs = Map.of();
            if (snapshot.has("inputs") && !snapshot.get("inputs").isNull()) {
                inputs = objectMapper.convertValue(
                        snapshot.get("inputs"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            }

            List<GraphRuntimeSnapshotResponse.RuntimeRepo> repos = new ArrayList<>();
            if (snapshot.has("repos") && snapshot.get("repos").isArray()) {
                for (JsonNode r : snapshot.get("repos")) {
                    repos.add(new GraphRuntimeSnapshotResponse.RuntimeRepo(
                            r.path("id").asText(),
                            r.path("url").asText(),
                            r.path("name").asText(),
                            r.path("test_command").asText(null),
                            r.path("agent_image").asText(null)));
                }
            }

            GraphRuntimeSnapshotResponse.TaskContext taskContext = buildTaskContext(run);

            return new GraphRuntimeSnapshotResponse(nodes, edges, inputs, repos, taskContext);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build graph runtime snapshot", e);
        }
    }

    /**
     * Resolves the triggering Task's identity directly off {@code run.getTaskId()} and the
     * {@code task.story_id -> story.epic_id} FK chain (Decision 1), for broadcast into every
     * node execution's {@code config.json} (Decision 3). Reads repositories directly rather than
     * {@code TaskService}/{@code EpicService} because this internal path has no request-scoped
     * tenant context for {@code checkOrgAccess} to consult, mirroring {@code RunService
     * .buildTaskSummary}'s identical choice. Absent when the run wasn't started from a Task;
     * Story/Epic are independently nullable if either no longer resolves (Caveat 1).
     */
    private @Nullable GraphRuntimeSnapshotResponse.TaskContext buildTaskContext(WorkflowRun run) {
        if (run.getTaskId() == null) {
            return null;
        }
        return taskRepo.findById(run.getTaskId())
                .map(task -> {
                    Story story = storyRepo.findById(task.getStoryId()).orElse(null);
                    Epic epic =
                            story != null ? epicRepo.findById(story.getEpicId()).orElse(null) : null;
                    return new GraphRuntimeSnapshotResponse.TaskContext(
                            task.getId(),
                            task.getTitle(),
                            story != null ? story.getId() : null,
                            story != null ? story.getTitle() : null,
                            epic != null ? epic.getId() : null,
                            epic != null ? epic.getTitle() : null,
                            resolveOpenBlockers(task.getId(), epic != null ? epic.getId() : null));
                })
                .orElse(null);
    }

    /**
     * The Task's actionable, root-cause open blocker(s) — not-yet-{@code done} items reachable by
     * walking the full blocking chain (not just the direct blocker) that are themselves unblocked,
     * i.e. worth acting on next (multi-step blocking chain feature, Decisions 3/4). Delegates to
     * {@link TransitiveReadinessResolver#rootCauseBlockersOf}, the same shared resolver {@link
     * DefaultRoadmapGraphService} uses, so the two call sites can no longer independently drift on
     * what "blocked" means. The transitive walk is bounded to the Task's own Epic's Story/Task set
     * when {@code epicId} is known (mirrors {@link DefaultRoadmapGraphService#assemble}'s
     * candidate-set bounding, Decision 2) — a direct blocker outside that Epic is still reported
     * (single-hop, as before), but its own upstream chain is not walked past. {@code epicId} is
     * null only when the Task's Story/Epic no longer resolves (Caveat 1 on {@link
     * #buildTaskContext}), in which case the walk covers just the Task's own direct edges.
     *
     * <p>Rows whose blocking item no longer resolves are silently skipped: {@code
     * work_item_dependency} has no DB-level foreign key on {@code blocking_item_id} (see {@code
     * V5__work_item_dependency.sql}), so a referenced Story/Task can be gone without the edge
     * itself being cleaned up (a race between fetching the edge and resolving it, or direct DB
     * manipulation) — the app-level delete paths clean up referencing edges defensively, but
     * nothing at the schema level guarantees it.
     */
    private List<OpenBlockerRef> resolveOpenBlockers(UUID taskId, @Nullable UUID epicId) {
        Set<UUID> candidateIds = new HashSet<>();
        candidateIds.add(taskId);
        if (epicId != null) {
            List<Story> epicStories = storyRepo.findByEpicIdOrderByCreatedAtDesc(epicId);
            List<UUID> epicStoryIds = epicStories.stream().map(Story::getId).toList();
            candidateIds.addAll(epicStoryIds);
            taskRepo.findByStoryIdIn(epicStoryIds).forEach(t -> candidateIds.add(t.getId()));
        }

        List<WorkItemDependency> rows =
                dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(candidateIds, candidateIds);

        Map<UUID, String> titleById = new HashMap<>();
        Map<UUID, String> statusById = new HashMap<>();
        Map<UUID, BlockableItemType> typeById = new HashMap<>();
        for (WorkItemDependency row : rows) {
            resolveBlockerTitleAndStatus(
                    row.getBlockingItemType(), row.getBlockingItemId(), titleById, statusById, typeById);
        }

        List<UUID> rootCauseIds = TransitiveReadinessResolver.rootCauseBlockersOf(taskId, rows, statusById::get);

        List<OpenBlockerRef> openBlockers = new ArrayList<>();
        for (UUID blockerId : rootCauseIds) {
            String title = titleById.get(blockerId);
            if (title == null) {
                continue; // referenced item no longer resolves (see javadoc above)
            }
            openBlockers.add(
                    new OpenBlockerRef(typeById.get(blockerId).name(), blockerId, title, statusById.get(blockerId)));
        }
        return openBlockers;
    }

    /** Resolves and caches a blocking item's title/status/type by id, once per {@code id}. */
    private void resolveBlockerTitleAndStatus(
            BlockableItemType type,
            UUID id,
            Map<UUID, String> titleById,
            Map<UUID, String> statusById,
            Map<UUID, BlockableItemType> typeById) {
        if (titleById.containsKey(id)) {
            return;
        }
        if (type == BlockableItemType.story) {
            Story blockingStory = storyRepo.findById(id).orElse(null);
            if (blockingStory == null) {
                return;
            }
            titleById.put(id, blockingStory.getTitle());
            // Stage-aware, like every other reader of "is this blocker cleared?": a Story a human
            // moved to `rolled_out` is cleared even if its Tasks say otherwise. Reading the bare
            // Task rollup here (as this did) made an agent see a shipped blocker as still open.
            statusById.put(
                    id,
                    RollupCalculator.effectiveStatus(
                            blockingStory.getStage(), taskRepo.findByStoryIdOrderByCreatedAtDesc(id)));
            typeById.put(id, BlockableItemType.story);
        } else {
            Task blockingTask = taskRepo.findById(id).orElse(null);
            if (blockingTask == null) {
                return;
            }
            titleById.put(id, blockingTask.getTitle());
            statusById.put(id, blockingTask.getStatus().name());
            typeById.put(id, BlockableItemType.task);
        }
    }

    public JobSecretHashResponse getJobSecretHash(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        return new JobSecretHashResponse(exec.getJobSecretHash());
    }

    public void updateExternalRunId(UUID runId, InternalUpdateExternalRunIdRequest req) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        run.setExternalRunId(req.externalRunId());
        runRepo.save(run);
    }

    public NodeExecutionResponse getNodeExecution(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        return toNodeExecResponse(exec);
    }

    public List<NodeExecutionResponse> getNodeExecutionsByRun(UUID runId) {
        return execRepo.findByWorkflowRunId(runId).stream()
                .map(this::toNodeExecResponse)
                .toList();
    }

    public List<PredecessorArtifactsResponse> getCompletedPredecessors(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        WorkflowRun run = runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Run not found: " + runId));

        List<NodeExecution> allExecs = execRepo.findByWorkflowRunId(runId);
        UUID targetNodeId = exec.getTemplateNodeId();

        try {
            var snapshot = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
            var edges = snapshot.get("edges");

            // Transitive BFS — find all ancestors
            Set<UUID> predecessorNodeIds = new HashSet<>();
            Queue<UUID> queue = new LinkedList<>();
            queue.add(targetNodeId);
            while (!queue.isEmpty()) {
                UUID current = queue.poll();
                if (edges != null) {
                    for (var edge : edges) {
                        UUID tgtId = UUID.fromString(edge.get("target_node_id").asText());
                        if (tgtId.equals(current)) {
                            UUID srcId =
                                    UUID.fromString(edge.get("source_node_id").asText());
                            if (predecessorNodeIds.add(srcId)) {
                                queue.add(srcId);
                            }
                        }
                    }
                }
            }

            // Build nodeId → label map from snapshot nodes
            Map<UUID, String> nodeLabels = new HashMap<>();
            var nodesArr = snapshot.get("nodes");
            if (nodesArr != null) {
                for (var n : nodesArr) {
                    nodeLabels.put(
                            UUID.fromString(n.get("template_node_id").asText()),
                            n.get("label").asText());
                }
            }

            return predecessorNodeIds.stream()
                    .map(predNodeId -> allExecs.stream()
                            .filter(e -> e.getTemplateNodeId().equals(predNodeId)
                                    && "completed".equals(e.getStatus().name().toLowerCase()))
                            .max(Comparator.comparingInt(NodeExecution::getIteration))
                            .map(e -> new PredecessorArtifactsResponse(
                                    e.getTemplateNodeId(),
                                    nodeLabels.getOrDefault(predNodeId, ""),
                                    e.getArtifactRefs(),
                                    e.getResult()))
                            .orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public String submitDecision(UUID runId, UUID nodeExecId, String decision) {
        // Load exec first so it can be passed to buildValidDecisionsWithSnapshot
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        DecisionsWithSnapshot dws = buildValidDecisionsWithSnapshot(runId, exec);
        List<String> validConditions = dws.validConditions();

        if (validConditions.isEmpty()) {
            throw new BadRequestException("This node has no conditional edges");
        }

        boolean matches = validConditions.stream().anyMatch(c -> c.equalsIgnoreCase(decision));
        if (!matches) {
            throw new BadRequestException("Invalid decision: '" + decision + "'. Valid options: " + validConditions);
        }

        // Store the canonical form matching the edge condition
        String canonical = validConditions.stream()
                .filter(c -> c.equalsIgnoreCase(decision))
                .findFirst()
                .orElse(decision);

        exec.setDecision(canonical);
        execRepo.save(exec);

        return canonical;
    }

    /**
     * Returns the valid decision strings (edge conditions) for the given node execution, by
     * walking the run's graph snapshot for outbound conditional edges from the execution's template
     * node.
     *
     * @throws NotFoundException if the execution or run does not exist
     */
    public List<String> getValidDecisions(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        return buildValidDecisionsWithSnapshot(runId, exec).validConditions();
    }

    private record DecisionsWithSnapshot(
            List<String> validConditions, com.fasterxml.jackson.databind.JsonNode snapshot) {}

    private DecisionsWithSnapshot buildValidDecisionsWithSnapshot(UUID runId, NodeExecution exec) {
        try {
            WorkflowRun run =
                    runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Run not found: " + runId));
            var snapshot = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
            List<String> validConditions = decisionOptionsResolver.resolve(snapshot, exec.getTemplateNodeId());
            return new DecisionsWithSnapshot(validConditions, snapshot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build graph snapshot", e);
        }
    }

    public String getDecision(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        return exec.getDecision();
    }

    /**
     * Creates an Epic on behalf of an agent pod. The Epic's target is resolved from the run's
     * {@code software_project_id} input; agents do not pick a target directly.
     *
     * <p>These internal endpoints exist because agent pods authenticate with JOB_SECRET (scoped
     * per-execution) and use /internal/ routes, whereas the public /api/v1/ endpoints use
     * different auth. Removing these would break deployed agent images that depend on the
     * create-proposal and list-proposals CLI scripts (Decision 6 — the scripts keep their names
     * and endpoint paths, now operating on Epics instead of the retired flat proposal).
     */
    @Transactional
    public EpicResponse createEpic(UUID runId, InternalCreateEpicRequest req) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        EpicRequest epicRequest =
                new EpicRequest(req.title(), req.description(), req.motivation(), softwareProjectId, req.priority());
        return epicService.create(epicRequest, runId);
    }

    /**
     * Updates an Epic on behalf of an agent pod. The Epic's ownership is validated against the
     * run's resolved {@code software_project_id} and {@code organization_id} before delegating to
     * {@link EpicService#updateInternal}.
     *
     * <p>See {@link #createEpic} for why these internal endpoints exist.
     */
    @Transactional
    public EpicResponse updateEpic(UUID runId, UUID epicId, InternalUpdateEpicRequest req) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        return epicService.updateInternal(epicId, softwareProjectId, runId, req);
    }

    /**
     * Lists Epics targeting the run's resolved software project.
     * See {@link #createEpic} for why these internal endpoints exist.
     */
    @Transactional(readOnly = true)
    public List<EpicResponse> listEpics(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        return epicService.listBySoftwareProjectId(softwareProjectId);
    }

    /**
     * Creates a Story under an Epic on behalf of an agent pod. New nested path added alongside
     * the preserved Epic-level trio (Decision 6/3.6) — a rolling-upgrade agent pod on an older
     * image never calls this, since it doesn't know the path exists.
     *
     * <p>The Epic's ownership is validated against the run's resolved {@code software_project_id}
     * (see {@link #createEpic}) before delegating to {@link StoryService#create(UUID, StoryRequest,
     * UUID, UUID)} — an org-only check isn't enough, since an org can span multiple
     * SoftwareProjects and the URL's {@code epicId} could otherwise belong to one outside this run.
     */
    @Transactional
    public StoryResponse createStory(UUID runId, UUID epicId, InternalCreateStoryRequest req) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        return storyService.create(
                epicId, new StoryRequest(req.title(), req.description(), req.priority()), runId, softwareProjectId);
    }

    /**
     * Creates a Task under a Story on behalf of an agent pod. See {@link #createStory}.
     *
     * <p>{@code epicId} comes from the URL's nested {@code .../{epicId}/stories/{storyId}/tasks}
     * segment purely to mirror {@link #createStory}'s shape; the Task itself is parented on
     * {@code storyId} alone (Decision 5). Validated against the Story's actual parent so a caller
     * can't create a Task under a {@code storyId} that doesn't belong to the {@code epicId} the
     * URL claims — a mismatch here almost certainly means the caller has a stale/wrong Epic id.
     * Also validated against the run's resolved {@code software_project_id}, same rationale as
     * {@link #createStory}.
     *
     * <p>The Story lookup goes through {@link StoryRepository} directly rather than
     * {@link StoryService#get}, because {@code get} calls {@link AuthorizationService#checkOrgAccess},
     * which reads the request-scoped tenant context. This method is reached only via the
     * {@code /internal/**} JOB_SECRET agent path (see {@link #createEpic}), which never has a
     * tenant context bound — the same reason every other internal-facing lookup in this class uses
     * {@code assertSameOrg}/repository access instead of the request-scoped {@code checkOrgAccess}.
     */
    @Transactional
    public TaskResponse createTask(UUID runId, UUID epicId, UUID storyId, InternalCreateTaskRequest req) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        Story story =
                storyRepo.findById(storyId).orElseThrow(() -> new NotFoundException("Story not found: " + storyId));
        if (!story.getEpicId().equals(epicId)) {
            throw new NotFoundException("Story " + storyId + " does not belong to epic " + epicId);
        }
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        return taskService.create(storyId, new TaskRequest(req.title(), req.description()), runId, softwareProjectId);
    }

    /**
     * Reads an Epic's full Roadmap Graph View (Decision 1, Decision 3) on behalf of an agent pod.
     * See {@link #createEpic} for why these internal endpoints exist; scoped the same way as
     * {@link #createStory}/{@link #createTask} via the run's resolved software project.
     */
    @Transactional(readOnly = true)
    public RoadmapGraphSnapshot getGraph(UUID runId, UUID nodeExecId, UUID epicId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        // Verify the node execution belongs to the requested run before reading the roadmap graph.
        NodeExecutionUtil.requireInRun(exec, runId);

        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        return roadmapGraphService.getGraph(epicId, runId, softwareProjectId);
    }

    /**
     * Reads the Roadmap Graph View for the Epic that owns the calling run's own triggering Task
     * (Decision 1, Decision 2) — lets an agent fetch its dependency context with no Epic ID at
     * all, resolving {@code run.task_id -> Task.story_id -> Story.epic_id} at request time rather
     * than relying on a client-side default computed once at pod start. {@code nodeExecId} is the
     * authenticated identifier on this route — {@code InternalAuthFilter} matches the caller's
     * JOB_SECRET against it — and is verified against {@code runId} before any resolution
     * proceeds; that resolution is then driven entirely by {@code runId}'s own {@code task_id}.
     * Mirrors {@link
     * #buildTaskContext} for the same FK chain, but throws {@link NotFoundException} on any
     * unresolved link (a 404 is the correct signal for this endpoint) instead of returning a
     * nullable summary DTO for narration — do not merge the two methods. Delegates to the same
     * {@link #getGraph(UUID, UUID, UUID)} authorization-checked path once {@code epicId} is
     * resolved (Decision 3), so this method adds no new org-aware code of its own.
     */
    @Transactional(readOnly = true)
    public RoadmapGraphSnapshot getGraphForTriggeringTask(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        // Verify the node execution belongs to the requested run before reading the roadmap graph.
        NodeExecutionUtil.requireInRun(exec, runId);

        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        if (run.getTaskId() == null) {
            throw new NotFoundException("Run " + runId + " was not started from a Task");
        }
        Task task = taskRepo.findById(run.getTaskId())
                .orElseThrow(() -> new NotFoundException("Task not found: " + run.getTaskId()));
        Story story = storyRepo
                .findById(task.getStoryId())
                .orElseThrow(() -> new NotFoundException("Story not found for task " + task.getId()));
        Epic epic = epicRepo.findById(story.getEpicId())
                .orElseThrow(() -> new NotFoundException("Epic not found for story " + story.getId()));
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        return roadmapGraphService.getGraph(epic.getId(), runId, softwareProjectId);
    }

    /**
     * Updates a Task's status (Decision 4) on behalf of an agent pod reporting a run's outcome.
     * See {@link #createEpic} for why these internal endpoints exist; scoped the same way as
     * {@link #createStory}/{@link #createTask} via the run's resolved software project. {@code
     * request.runId()}, if supplied, is the run being reported ON (verified against the Task's
     * most recent linked run) — separate from this method's own {@code runId}, which identifies
     * the calling agent pod for auth/scoping purposes; in the common case these are the same run.
     */
    @Transactional
    public TaskResponse updateTaskStatus(UUID runId, UUID nodeExecId, UUID taskId, TaskStatusUpdateRequest request) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        // Verify the node execution belongs to the requested run before updating the task's status.
        NodeExecutionUtil.requireInRun(exec, runId);

        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        UUID softwareProjectId = resolveSoftwareProjectIdFromRun(run);
        return taskService.updateStatusInternal(
                taskId, request.status(), runId, softwareProjectId, request.runId(), request.note());
    }

    /**
     * Resolves a single {@code software_project_id} from a run's inputs. Resolution order:
     * <ol>
     *   <li>direct {@code software_project_id} field on inputs;</li>
     *   <li>schema-driven discovery over fields typed {@code software_project_id};</li>
     *   <li>legacy {@code git_repo_id} field (post-V45, git_repo.id IS the software_project.id);</li>
     *   <li>schema-driven discovery over fields typed {@code git_repo} (legacy templates).</li>
     * </ol>
     * Throws {@link NotFoundException} if nothing resolves.
     */
    @Transactional(readOnly = true)
    UUID resolveSoftwareProjectIdFromRun(WorkflowRun run) {
        JsonNode inputs;
        try {
            inputs = objectMapper.readTree(run.getInputs());
        } catch (JsonProcessingException e) {
            throw new NotFoundException(
                    "Could not parse run inputs JSON for run: " + run.getId() + " (" + e.getOriginalMessage() + ")");
        }

        JsonNode schema = null;
        GraphTemplate template =
                graphTemplateRepo.findById(run.getGraphTemplateId()).orElse(null);
        if (template != null && template.getInputSchema() != null) {
            try {
                JsonNode parsed = objectMapper.readTree(template.getInputSchema());
                if (parsed.isArray()) schema = parsed;
            } catch (JsonProcessingException ignore) {
                // malformed schema — treat as no schema
            }
        }

        // 1. Direct software_project_id field.
        UUID resolved = tryResolveDirect(inputs, "software_project_id");
        if (resolved != null) return resolved;

        // 2. Schema-driven discovery over software_project_id-typed fields (handles renamed inputs).
        if (schema != null) {
            resolved = tryResolveBySchemaType(schema, inputs, "software_project_id");
            if (resolved != null) return resolved;
        }

        // 3. Legacy git_repo_id field. git_repo.id IS software_project.id post-V45.
        resolved = tryResolveDirect(inputs, "git_repo_id");
        if (resolved != null) return resolved;

        // 4. Schema-driven git_repo-typed fields (legacy templates with renamed inputs).
        if (schema != null) {
            resolved = tryResolveBySchemaType(schema, inputs, "git_repo");
            if (resolved != null) return resolved;
        }

        throw new NotFoundException("Could not resolve software_project_id from run inputs for run: " + run.getId());
    }

    private UUID tryResolveDirect(JsonNode inputs, String fieldName) {
        JsonNode node = inputs.get(fieldName);
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try {
            UUID candidate = UUID.fromString(node.asText());
            return softwareProjectRepo.existsById(candidate) ? candidate : null;
        } catch (IllegalArgumentException ignore) {
            return null;
        }
    }

    private UUID tryResolveBySchemaType(JsonNode schema, JsonNode inputs, String typeName) {
        for (JsonNode field : schema) {
            if (typeName.equals(field.path("type").asText(""))) {
                String name = field.path("name").asText("");
                JsonNode val = inputs.get(name);
                if (val == null || val.isNull() || val.asText().isBlank()) continue;
                try {
                    UUID candidate = UUID.fromString(val.asText());
                    if (softwareProjectRepo.existsById(candidate)) return candidate;
                } catch (IllegalArgumentException ignore) {
                    // not a UUID — keep scanning
                }
            }
        }
        return null;
    }

    /**
     * Checks whether a node has any outgoing edge with a non-null condition.
     * Reuses the same snapshot/edge logic as submitDecision.
     */
    private boolean hasConditionalEdges(UUID runId, UUID templateNodeId) {
        try {
            WorkflowRun run =
                    runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Run not found: " + runId));
            var snapshot = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
            var edges = snapshot.get("edges");
            if (edges == null) return false;
            for (var edge : edges) {
                if (edge.get("source_node_id").asText().equals(templateNodeId.toString())
                        && edge.has("condition")
                        && !edge.get("condition").isNull()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false; // fail open — don't block completion
        }
    }

    /**
     * Builds the internal/agent-facing (`/internal/**`) view of a node execution. Always leaves
     * {@code requiredArtifacts}/{@code candidateBreakdown}/{@code escalation} null — those are
     * human-gate UI concerns (Approvals dashboard, Run Detail page); an agent reads its own inputs
     * from the workspace, not this DTO.
     */
    private NodeExecutionResponse toNodeExecResponse(NodeExecution e) {
        UUID[] edges = e.getTraversedEdgeIds();
        List<UUID> edgeList = edges == null ? null : Arrays.asList(edges);
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
                null,
                null,
                null);
    }

    public void setTraversedEdges(UUID runId, UUID nodeExecId, InternalSetTraversedEdgesRequest req) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        if (!exec.getWorkflowRunId().equals(runId)) {
            throw new NotFoundException("Node execution not found: " + nodeExecId);
        }
        List<UUID> ids = req.edgeIds() == null ? List.of() : req.edgeIds();
        exec.setTraversedEdgeIds(ids.toArray(new UUID[0]));
        execRepo.save(exec);
        // Validates the run exists; org no longer needed (feeds published org-free, re-scoped downstream).
        runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        eventPublisher.publishNodeStatusChanged(
                runId, nodeExecId, exec.getStatus().name());
    }
}
