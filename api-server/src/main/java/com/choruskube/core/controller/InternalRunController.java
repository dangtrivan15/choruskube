package com.choruskube.core.controller;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.*;
import com.choruskube.core.service.InternalRunService;
import com.choruskube.core.service.RunPullRequestService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/runs")
public class InternalRunController {

    private final InternalRunService service;
    private final GitHubCredentialResolver gitHubCredentialResolver;
    private final RunPullRequestService runPullRequestService;

    public InternalRunController(
            InternalRunService service,
            GitHubCredentialResolver gitHubCredentialResolver,
            RunPullRequestService runPullRequestService) {
        this.service = service;
        this.gitHubCredentialResolver = gitHubCredentialResolver;
        this.runPullRequestService = runPullRequestService;
    }

    @PostMapping("/{runId}/node-executions")
    @ResponseStatus(HttpStatus.CREATED)
    public NodeExecutionResponse createNodeExecution(
            @PathVariable UUID runId, @RequestBody InternalCreateNodeExecutionRequest request) {
        return service.createNodeExecution(runId, request);
    }

    @PutMapping("/{runId}/node-executions/{nodeExecId}/status")
    public NodeExecutionResponse updateNodeExecutionStatus(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody InternalUpdateNodeExecutionRequest request) {
        return service.updateNodeExecutionStatus(runId, nodeExecId, request);
    }

    @PutMapping("/{runId}/node-executions/{nodeExecId}/traversed-edges")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setTraversedEdges(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @RequestBody InternalSetTraversedEdgesRequest request) {
        service.setTraversedEdges(runId, nodeExecId, request);
    }

    @GetMapping("/{runId}/status")
    public RunStatusResponse getRunStatus(@PathVariable UUID runId) {
        return service.getRunStatus(runId);
    }

    @PutMapping("/{runId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateRunStatus(@PathVariable UUID runId, @RequestBody InternalUpdateRunStatusRequest request) {
        service.updateRunStatus(runId, request);
    }

    @PostMapping("/{runId}/node-executions/{nodeExecId}/logs")
    @ResponseStatus(HttpStatus.CREATED)
    public void writeExecutionLog(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody InternalWriteLogRequest request) {
        service.writeExecutionLog(runId, nodeExecId, request);
    }

    @PostMapping("/{runId}/review-history")
    @ResponseStatus(HttpStatus.CREATED)
    public void writeReviewHistory(@PathVariable UUID runId, @RequestBody InternalWriteReviewHistoryRequest request) {
        service.writeReviewHistory(runId, request);
    }

    @GetMapping("/{runId}/review-history")
    public List<ReviewHistoryResponse> getReviewHistory(
            @PathVariable UUID runId, @RequestParam(required = false) String loopGroup) {
        return service.getReviewHistory(runId, loopGroup);
    }

    @GetMapping("/{runId}/graph-runtime")
    public GraphRuntimeSnapshotResponse getGraphRuntimeSnapshot(@PathVariable UUID runId) {
        return service.getGraphRuntimeSnapshot(runId);
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/job-secret-hash")
    public JobSecretHashResponse getJobSecretHash(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return service.getJobSecretHash(runId, nodeExecId);
    }

    @PutMapping("/{runId}/external-run-id")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateExternalRunId(@PathVariable UUID runId, @RequestBody InternalUpdateExternalRunIdRequest request) {
        service.updateExternalRunId(runId, request);
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}")
    public NodeExecutionResponse getNodeExecution(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return service.getNodeExecution(runId, nodeExecId);
    }

    @GetMapping("/{runId}/node-executions")
    public List<NodeExecutionResponse> getNodeExecutionsByRun(@PathVariable UUID runId) {
        return service.getNodeExecutionsByRun(runId);
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/predecessors")
    public List<PredecessorArtifactsResponse> getCompletedPredecessors(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return service.getCompletedPredecessors(runId, nodeExecId);
    }

    @PutMapping("/{runId}/node-executions/{nodeExecId}/decision")
    public DecisionResponse submitDecision(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @RequestBody SubmitDecisionRequest request) {
        // Invalid decisions surface as BadRequestException → 400 via GlobalExceptionHandler.
        return new DecisionResponse(service.submitDecision(runId, nodeExecId, request.decision()));
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/decision")
    public DecisionResponse getDecision(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return new DecisionResponse(service.getDecision(runId, nodeExecId));
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/valid-decisions")
    public ValidDecisionsResponse getValidDecisions(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return new ValidDecisionsResponse(service.getValidDecisions(runId, nodeExecId));
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/github-token")
    public Map<String, String> getGitHubToken(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        service.getNodeExecution(runId, nodeExecId); // validates nodeExecId belongs to runId
        return Map.of("token", gitHubCredentialResolver.getTokenForRun(runId));
    }

    /**
     * Internal endpoints for Epic/Story/Task management from agent pods.
     * Agent pods use JOB_SECRET auth and the /internal/ route prefix, which is
     * different from the public /api/v1/epics|stories|tasks endpoints that use
     * user-facing auth. These endpoints also auto-resolve the software project
     * from the run context so agents don't need to know it.
     *
     * The create/list/update paths below are kept byte-for-byte unchanged from the retired
     * flat-proposal model (Decision 6) — only their delegate calls now operate on Epics — because
     * the agent-images/claude-code/create-proposal, list-proposals, and update-proposal CLI
     * scripts depend on these exact paths. Removing or renaming them would break deployed agent
     * images still running an older image during a rolling upgrade. The nested Story/Task-create
     * paths are genuinely new, added under the same preserved segment so a rolling-upgrade agent
     * pod on the old image never sees them.
     */
    @PostMapping("/{runId}/node-executions/{nodeExecId}/feature-proposals")
    @ResponseStatus(HttpStatus.CREATED)
    public EpicResponse createFeatureProposal(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @Valid @RequestBody InternalCreateEpicRequest request) {
        return service.createEpic(runId, request);
    }

    @GetMapping("/{runId}/node-executions/{nodeExecId}/feature-proposals")
    public List<EpicResponse> listFeatureProposals(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return service.listEpics(runId);
    }

    @PatchMapping("/{runId}/node-executions/{nodeExecId}/feature-proposals/{proposalId}")
    public EpicResponse updateFeatureProposal(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @PathVariable UUID proposalId,
            @Valid @RequestBody InternalUpdateEpicRequest request) {
        return service.updateEpic(runId, proposalId, request);
    }

    /** New: create a Story under an Epic (Decision 6/3.6). */
    @PostMapping("/{runId}/node-executions/{nodeExecId}/feature-proposals/{epicId}/stories")
    @ResponseStatus(HttpStatus.CREATED)
    public StoryResponse createStory(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @PathVariable UUID epicId,
            @Valid @RequestBody InternalCreateStoryRequest request) {
        return service.createStory(runId, epicId, request);
    }

    /** New: create a Task under a Story (Decision 6/3.6). */
    @PostMapping("/{runId}/node-executions/{nodeExecId}/feature-proposals/{epicId}/stories/{storyId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse createTask(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @PathVariable UUID epicId,
            @PathVariable UUID storyId,
            @Valid @RequestBody InternalCreateTaskRequest request) {
        return service.createTask(runId, epicId, storyId, request);
    }

    /**
     * Agent-facing mirror of {@code GET /api/v1/epics/{epicId}/graph} (Roadmap Graph View,
     * Decision 1) — same response shape (readiness, capped run history), scoped by the calling
     * run's project rather than a JWT-derived org (Decision 5).
     */
    @GetMapping("/{runId}/node-executions/{nodeExecId}/feature-proposals/{epicId}/graph")
    public RoadmapGraphSnapshot getGraph(
            @PathVariable UUID runId, @PathVariable UUID nodeExecId, @PathVariable UUID epicId) {
        return service.getGraph(runId, epicId);
    }

    /**
     * Agent-facing "resolve it for me" sibling of {@link #getGraph} (Decision 1, Decision 2) — no
     * Epic ID in the path; the Epic is derived server-side from the calling run's own triggering
     * Task ({@code run.task_id -> Task -> Story -> Epic}), so a plain {@code get-roadmap-graph}
     * with no flags works even when the client-side {@code $EPIC_ID} default never resolved.
     */
    @GetMapping("/{runId}/node-executions/{nodeExecId}/graph")
    public RoadmapGraphSnapshot getGraphForCurrentTask(@PathVariable UUID runId, @PathVariable UUID nodeExecId) {
        return service.getGraphForTriggeringTask(runId, nodeExecId);
    }

    /**
     * Agent-facing mirror of {@code PATCH /api/v1/tasks/{id}/status} (Decision 1, Decision 4) —
     * lets an agent report a Task's outcome (success or failure) from inside its own run, scoped
     * by the calling run's project rather than a JWT-derived org (Decision 5).
     */
    @PatchMapping("/{runId}/node-executions/{nodeExecId}/tasks/{taskId}/status")
    public TaskResponse updateTaskStatus(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @PathVariable UUID taskId,
            @Valid @RequestBody TaskStatusUpdateRequest request) {
        return service.updateTaskStatus(runId, taskId, request);
    }

    @PostMapping("/{runId}/node-executions/{nodeExecId}/pull-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RunPullRequestResponse createPullRequest(
            @PathVariable UUID runId,
            @PathVariable UUID nodeExecId,
            @Valid @RequestBody CreateRunPullRequestRequest request) {
        return runPullRequestService.createPullRequest(runId, nodeExecId, request);
    }

    @GetMapping("/{runId}/pull-requests")
    public List<RunPullRequestResponse> getPullRequests(@PathVariable UUID runId) {
        return runPullRequestService.getPullRequests(runId);
    }
}
