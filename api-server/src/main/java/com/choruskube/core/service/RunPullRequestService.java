package com.choruskube.core.service;

import com.choruskube.core.dto.CreateRunPullRequestRequest;
import com.choruskube.core.dto.RunPullRequestResponse;
import com.choruskube.core.event.MappableCreated;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.NodeExecutionUtil;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunPullRequestService {

    private final RunPullRequestRepository prRepo;
    private final WorkflowRunRepository runRepo;
    private final GitRepoRepository gitRepoRepo;
    private final NodeExecutionRepository execRepo;
    private final RunEventPublisher eventPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;

    public RunPullRequestService(
            RunPullRequestRepository prRepo,
            WorkflowRunRepository runRepo,
            GitRepoRepository gitRepoRepo,
            NodeExecutionRepository execRepo,
            RunEventPublisher eventPublisher,
            ApplicationEventPublisher applicationEventPublisher) {
        this.prRepo = prRepo;
        this.runRepo = runRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.execRepo = execRepo;
        this.eventPublisher = eventPublisher;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public RunPullRequestResponse createPullRequest(UUID runId, UUID nodeExecId, CreateRunPullRequestRequest req) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));

        // Verify the node execution belongs to the requested run before registering a pull request
        // for it — this row is later read by the run's own check-prs completion gate.
        NodeExecutionUtil.requireInRun(exec, runId);

        // Validate the run exists; ownership of the PR row is written from the MappableCreated
        // withParent event below (resolved from the parent run), not stamped on the entity.
        runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        GitRepo gitRepo = gitRepoRepo
                .findById(req.gitRepoId())
                .orElseThrow(() -> new NotFoundException("Git repo not found: " + req.gitRepoId()));

        // Re-registering the same (runId, prUrl) refreshes mutable metadata in place rather
        // than inserting a duplicate row. Agents may call register-pr more than once per run
        // (Code Review refreshing a PR Implement already opened, a Test-failure retry, a fallback
        // PR's cross-link step, etc. — see Decision 1/§3.2 in this workflow's spec), and the run
        // detail page would otherwise render the same PR twice.
        RunPullRequest pr = prRepo.findByWorkflowRunIdAndPrUrl(runId, req.prUrl())
                .orElseGet(() -> {
                    RunPullRequest fresh = new RunPullRequest();
                    fresh.setWorkflowRunId(runId);
                    fresh.setPrUrl(req.prUrl());
                    return fresh;
                });
        pr.setGitRepoId(req.gitRepoId());
        pr.setNodeExecutionId(nodeExecId);
        pr.setPrNumber(req.prNumber());
        pr.setTitle(req.title());
        pr.setRepoName(req.repoName());
        pr = prRepo.save(pr);
        applicationEventPublisher.publishEvent(
                MappableCreated.withParent("run_pull_request", pr.getId(), "workflow_run", pr.getWorkflowRunId()));

        eventPublisher.publishPullRequestCreated(runId);

        return toResponse(pr, gitRepo.getUrl());
    }

    /**
     * Node-execution-scoped read used by {@code InternalRunController#getPullRequestsForNodeExecution}
     * (Decision 3/3.3 — PR completion gate; {@code check-prs} calls this via the {@code
     * /internal/runs/{runId}/node-executions/{nodeExecId}/pull-requests} endpoint). {@code
     * InternalAuthFilter} only checks that the caller's {@code JOB_SECRET} hash matches {@code
     * nodeExecId}'s own stored hash — it never cross-checks the {@code runId} segment in the same
     * path against that execution's actual {@code workflow_run_id}. Without the check below, a
     * caller could substitute an arbitrary {@code runId} while presenting its own valid {@code
     * nodeExecId} and read a completely unrelated run's PR list. This mirrors the same {@code
     * nodeExecId}-belongs-to-{@code runId} guard {@link
     * com.choruskube.core.service.InternalRunService#setTraversedEdges} already performs for
     * exactly this reason; the message intentionally doesn't distinguish "wrong run" from
     * "execution doesn't exist" so it can't be used to probe whether a given nodeExecId is valid
     * for some other run.
     */
    public List<RunPullRequestResponse> getPullRequestsForNodeExecution(UUID runId, UUID nodeExecId) {
        NodeExecution exec = execRepo.findById(nodeExecId)
                .orElseThrow(() -> new NotFoundException("Node execution not found: " + nodeExecId));
        if (!exec.getWorkflowRunId().equals(runId)) {
            throw new NotFoundException("Node execution not found: " + nodeExecId);
        }
        return getPullRequests(runId);
    }

    public List<RunPullRequestResponse> getPullRequests(UUID runId) {
        List<RunPullRequest> pullRequests = prRepo.findByWorkflowRunId(runId);
        if (pullRequests.isEmpty()) {
            return List.of();
        }

        // Batch-load all referenced git repos to avoid N+1 queries
        Set<UUID> repoIds =
                pullRequests.stream().map(RunPullRequest::getGitRepoId).collect(Collectors.toSet());
        Map<UUID, String> repoUrlMap =
                gitRepoRepo.findAllById(repoIds).stream().collect(Collectors.toMap(GitRepo::getId, GitRepo::getUrl));

        return pullRequests.stream()
                .map(pr -> toResponse(pr, repoUrlMap.getOrDefault(pr.getGitRepoId(), "")))
                .toList();
    }

    private RunPullRequestResponse toResponse(RunPullRequest pr, String repoUrl) {
        return new RunPullRequestResponse(
                pr.getId(),
                pr.getWorkflowRunId(),
                pr.getGitRepoId(),
                pr.getNodeExecutionId(),
                pr.getPrUrl(),
                pr.getPrNumber(),
                pr.getTitle(),
                pr.getRepoName(),
                repoUrl,
                pr.getCreatedAt(),
                pr.getState() == null ? null : pr.getState().name(),
                pr.getMergedAt());
    }
}
