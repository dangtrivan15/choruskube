package com.choruskube.core.service;

import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes a Task as part of the state change that finishes its run. Final Approval merges a run's
 * pull requests before the run can finish, so by the time a run reaches a terminal status its
 * merges are already recorded, and "the run finished" and "the Task is done" can be one commit.
 *
 * <p>There is deliberately no other automatic closer: no scan of GitHub, no sweep for Tasks left
 * open. Both places that move a run to a terminal status — the orchestrator's report and a human's
 * cancel — call this inside their own transaction, so a finished run with everything merged and its
 * Task still open is a state that never commits.
 */
@Service
public class TaskSettlementService {

    private static final Logger log = LoggerFactory.getLogger(TaskSettlementService.class);

    private final TaskRepository taskRepo;
    private final WorkflowRunRepository runRepo;
    private final RunPullRequestRepository prRepo;
    private final EntityManager entityManager;
    private final RunEventPublisher eventPublisher;
    private final TaskGithubIssueCloser issueCloser;
    private final TransactionTemplate requiresNewTx;

    public TaskSettlementService(
            TaskRepository taskRepo,
            WorkflowRunRepository runRepo,
            RunPullRequestRepository prRepo,
            EntityManager entityManager,
            RunEventPublisher eventPublisher,
            TaskGithubIssueCloser issueCloser,
            PlatformTransactionManager txManager) {
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.prRepo = prRepo;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.issueCloser = issueCloser;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Marks the run's Task done if this run settles it: the run is terminal, it is the Task's latest
     * run, the Task is still in progress, and the run registered at least one pull request and every
     * one of them has merged. Otherwise does nothing.
     *
     * <p><strong>Idempotent.</strong> A Task that is already done — by an earlier report of the same
     * finish, or by a human — is left alone, so the orchestrator retrying its status report is safe.
     *
     * <p><strong>{@code MANDATORY}.</strong> The finish and the closure commit together or not at
     * all; a caller without a transaction would commit the finish on its own and could lose the
     * closure to a crash in between.
     *
     * <p><strong>Never signals "not settled" by throwing.</strong> An exception crossing this
     * proxy marks the caller's transaction rollback-only even if the caller catches it, which would
     * undo the run's finish over a Task that merely was not ready to close.
     *
     * @return whether this call closed the Task
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean closeIfSettledBy(WorkflowRun run) {
        if (run.getTaskId() == null || !WorkflowRunStatusGroups.TERMINAL.contains(run.getStatus())) {
            return false;
        }
        // At least one, because a run that opened none has nothing whose merge says the work landed.
        List<RunPullRequest> pullRequests = prRepo.findByWorkflowRunId(run.getId());
        if (pullRequests.isEmpty() || pullRequests.stream().anyMatch(pr -> pr.getMergedAt() == null)) {
            return false;
        }
        // Locked before the status is read: a human's Complete racing this finish would otherwise
        // both see in_progress and both mark it done.
        Task task = taskRepo.findWithLockById(run.getTaskId()).orElse(null);
        if (task == null) {
            return false;
        }
        entityManager.refresh(task);
        if (task.getStatus() != WorkItemStatus.in_progress || !isLatestRunOf(task.getId(), run.getId())) {
            return false;
        }

        task.setStatus(WorkItemStatus.done);
        taskRepo.save(task);
        eventPublisher.publishRoadmapItemChanged(
                "task", task.getId(), task.getStatus().name());
        closeLinkedIssueAfterCommit(task.getId());
        log.info("Closed Task {} — run {} finished with every pull request merged", task.getId(), run.getId());
        return true;
    }

    private boolean isLatestRunOf(UUID taskId, UUID runId) {
        return runRepo.findByTaskIdOrderByCreatedAtDesc(taskId, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(latest -> latest.getId().equals(runId))
                .orElse(false);
    }

    /**
     * A GitHub call, so never inside the finish: it would hold the run and Task rows locked for as
     * long as GitHub takes, and it would close the issue of a finish that then rolled back. {@code
     * REQUIRES_NEW} because a write from {@code afterCommit} otherwise joins the transaction that has
     * already committed and is silently never saved.
     */
    private void closeLinkedIssueAfterCommit(UUID taskId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                requiresNewTx.executeWithoutResult(status -> issueCloser.close(taskId));
            }
        });
    }
}
