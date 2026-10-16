package com.choruskube.core.service;

import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.CloseTaskIssueWorkflowInput;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes a done Task's linked GitHub issue, best-effort: starts a workflow after the completion
 * commits, and a Worker of the run's Fleet makes the GitHub call — waiting, not failing, while no
 * Worker is live. A failed start is logged and never retried.
 */
@Component
public class TaskGithubIssueCloser {

    static final String WORKFLOW_TYPE = "CloseTaskIssueWorkflow";
    static final String WORKFLOW_ID_PREFIX = "choruskube-task-issue-close-";

    private static final Logger log = LoggerFactory.getLogger(TaskGithubIssueCloser.class);

    private final TaskGithubIssueRepository taskGithubIssueRepository;
    private final GitRepoRepository gitRepoRepo;
    private final RunPlacementService placements;
    private final WorkflowClientRegistry workflowClients;
    private final String orchestratorTaskQueue;
    private final TransactionTemplate requiresNewTx;

    public TaskGithubIssueCloser(
            TaskGithubIssueRepository taskGithubIssueRepository,
            GitRepoRepository gitRepoRepo,
            RunPlacementService placements,
            WorkflowClientRegistry workflowClients,
            @Value("${temporal.task-queue}") String orchestratorTaskQueue,
            PlatformTransactionManager txManager) {
        this.taskGithubIssueRepository = taskGithubIssueRepository;
        this.gitRepoRepo = gitRepoRepo;
        this.placements = placements;
        this.workflowClients = workflowClients;
        this.orchestratorTaskQueue = orchestratorTaskQueue;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Starts the close once the caller's transaction commits, so a completion that rolls back never
     * closes its issue. Does nothing else here: placement can throw, and an exception crossing a
     * transactional proxy would mark the completion rollback-only even if caught.
     */
    public void closeAfterCommit(UUID taskId, UUID runId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("closeAfterCommit requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // An exception thrown here reaches the request whose completion already committed.
                try {
                    startClose(taskId, runId);
                } catch (Exception e) {
                    log.warn("Failed to start the GitHub issue close for Task {}: {}", taskId, e.getMessage());
                }
            }
        });
    }

    private void startClose(UUID taskId, UUID runId) {
        record Start(CloseTaskIssueWorkflowInput input, String namespace) {}

        // REQUIRES_NEW: the completion's transaction has already committed, and a placement
        // implementation may demand one of its own.
        Optional<Start> start = requiresNewTx.execute(status -> {
            Optional<TaskGithubIssue> linkage = taskGithubIssueRepository.findByTaskId(taskId);
            if (linkage.isEmpty() || linkage.get().getState() != GithubIssueState.open) {
                return Optional.<Start>empty();
            }
            TaskGithubIssue issue = linkage.get();
            GitRepo gitRepo = gitRepoRepo
                    .findById(issue.getGitRepoId())
                    .orElseThrow(() -> new NotFoundException("GitRepo not found: " + issue.getGitRepoId()));
            RunPlacement placement = placements.placeFor(runId);
            return Optional.of(new Start(
                    new CloseTaskIssueWorkflowInput(
                            taskId,
                            runId,
                            RepoNameUtil.deriveOwnerRepoName(gitRepo.getUrl()),
                            issue.getIssueNumber(),
                            placement.taskQueue()),
                    placement.namespace()));
        });
        if (start == null || start.isEmpty()) {
            return;
        }
        Start s = start.get();

        WorkflowOptions options = WorkflowOptions.newBuilder()
                .setWorkflowId(WORKFLOW_ID_PREFIX + taskId)
                .setTaskQueue(orchestratorTaskQueue)
                .build();
        try {
            workflowClients
                    .clientFor(s.namespace())
                    .newUntypedWorkflowStub(WORKFLOW_TYPE, options)
                    .start(s.input());
        } catch (WorkflowExecutionAlreadyStarted e) {
            log.info("GitHub issue close for Task {} already started", taskId);
            return;
        }
        log.info(
                "Started GitHub issue close for Task {} (run {}) in namespace {} on Worker queue {}",
                taskId,
                runId,
                s.namespace(),
                s.input().workerTaskQueue());
    }

    public enum RecordOutcome {
        RECORDED,
        ALREADY_CLOSED,
        NOT_FOUND,
        MISMATCH
    }

    /** Records the issue closed, only if the linkage is still open and has this issue number. */
    @Transactional
    public RecordOutcome recordClosed(UUID taskId, int issueNumber) {
        if (taskGithubIssueRepository.markClosed(taskId, issueNumber, Instant.now()) == 1) {
            return RecordOutcome.RECORDED;
        }
        Optional<TaskGithubIssue> linkage = taskGithubIssueRepository.findByTaskId(taskId);
        if (linkage.isEmpty()) {
            return RecordOutcome.NOT_FOUND;
        }
        if (linkage.get().getIssueNumber() != issueNumber) {
            return RecordOutcome.MISMATCH;
        }
        return RecordOutcome.ALREADY_CLOSED;
    }
}
