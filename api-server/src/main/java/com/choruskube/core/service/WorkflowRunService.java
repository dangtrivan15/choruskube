package com.choruskube.core.service;

import com.choruskube.core.repository.TombstonedWorkflowRunRef;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lifecycle cascade primitives for {@code workflow_run}. Split off from {@code RunService}
 * (runtime concerns — start / signal / result) because (a) the run-deletion cascade
 * delegates to this service via a small, focused surface, and (b) the soft-delete +
 * reconciler machinery fits the per-service-owns-its-external-state pattern.
 *
 * <p>External side effects handled here:
 * <ul>
 *   <li><b>Temporal workflow termination</b> — {@link #cleanupAndHardDelete(UUID, String)}
 *       calls {@code workflowClient.newUntypedWorkflowStub(externalRunId).terminate(...)}
 *       before the DB hard-delete. Idempotent: {@link WorkflowNotFoundException} is
 *       swallowed (matches {@code RunService}'s existing signal-call pattern).</li>
 *   <li><b>Object storage artifact cleanup</b> — <b>deliberately NOT handled here</b>. A
 *       follow-up object storage reconciler is needed; orphan risk is unbounded bucket
 *       bloat, an acknowledged debt.</li>
 * </ul>
 *
 * <p>DB child cleanup (node_execution → execution_log) is handled transparently by the V1
 * {@code ON DELETE CASCADE} chain — no app-level enumeration needed.
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);

    private static final String TERMINATE_REASON = "org-delete cascade";

    private final WorkflowRunRepository repo;
    private final WorkflowClient workflowClient;
    private final TransactionTemplate requiresNewTx;
    private final Executor workflowTerminationExecutor;

    public WorkflowRunService(
            WorkflowRunRepository repo,
            WorkflowClient workflowClient,
            PlatformTransactionManager txManager,
            @Qualifier("workflowTerminationExecutor") Executor workflowTerminationExecutor) {
        this.repo = repo;
        this.workflowClient = workflowClient;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.workflowTerminationExecutor = workflowTerminationExecutor;
    }

    // -----------------------------------------------------------------------
    // Cascade: soft-delete + afterCommit + reconciler
    // -----------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteAllByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        // findAllById honors @SQLRestriction → we only snapshot LIVE rows.
        List<SnapshotRef> toCleanup = repo.findAllById(ids).stream()
                .map(r -> new SnapshotRef(r.getId(), r.getExternalRunId()))
                .toList();

        int updated = repo.softDeleteAllByIds(ids, Instant.now());
        if (updated == 0) {
            return;
        }
        log.info("Soft-deleted {} workflow_run row(s) by id", updated);

        if (toCleanup.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (SnapshotRef ref : toCleanup) {
                    CompletableFuture.runAsync(
                            () -> safeCleanup(ref.id(), ref.externalRunId()), workflowTerminationExecutor);
                }
            }
        });
    }

    /**
     * Shared cleanup primitive for a single tombstoned run. Called from both the afterCommit
     * hook and {@link #reconcileTombstonedBatch(int)}. Idempotent on both legs: Temporal
     * terminate swallows {@link WorkflowNotFoundException}; DB hard-delete is predicate-scoped.
     *
     * <p>If Temporal terminate throws a non-not-found exception, the DB hard-delete is still
     * attempted — for an FK-free hard-delete this is fine, and the tombstone persisting past
     * the Temporal failure is captured by the NEXT reconciler tick's terminate retry.
     * Actually: the DB hard-delete is wrapped in REQUIRES_NEW and swallows nothing, so any
     * Temporal exception propagates to the caller; the reconciler's per-item try/catch
     * swallows there. The afterCommit hook's {@link #safeCleanup} wrapper does the same.
     *
     * <p>Package-private so the reconciler-scoped public entry point can use it.
     */
    void cleanupAndHardDelete(UUID runId, String externalRunId) {
        tryTerminateWorkflow(runId, externalRunId);
        // REQUIRES_NEW — isolates the DELETE from any outer TX (notably the test harness).
        // In production both call sites (afterCommit, reconciler) run without an outer TX.
        Integer rowsDeleted = requiresNewTx.execute(status -> repo.hardDeleteTombstoneById(runId));
        if (rowsDeleted != null && rowsDeleted > 0) {
            log.info("Hard-deleted tombstoned workflow_run {}", runId);
        }
    }

    private void tryTerminateWorkflow(UUID runId, String externalRunId) {
        if (externalRunId == null || externalRunId.isBlank()) {
            // Run was tombstoned before a Temporal workflow was started (e.g., run-creation
            // failure path). Nothing to terminate.
            return;
        }
        try {
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub(externalRunId);
            stub.terminate(TERMINATE_REASON);
        } catch (WorkflowNotFoundException e) {
            // Workflow already completed or never reached Temporal. Idempotent — swallow.
            log.debug("Temporal workflow {} already gone for run {}", externalRunId, runId);
        } catch (Exception e) {
            // Temporal unreachable, auth error, etc. Swallow; reconciler retries next tick.
            log.warn("Temporal terminate for workflow {} (run {}) failed: {}", externalRunId, runId, e.getMessage());
            // Re-throw so the caller can skip the DB hard-delete for this round — keeping
            // Temporal and DB in lockstep per the plan's "no DB row removed while external
            // resource still exists" invariant.
            throw e;
        }
    }

    /**
     * Reconciler driver entry point: fetch up to {@code batchSize} tombstoned runs and clean
     * each. Exposed on the service so the reconciler class stays thin and logging lives here.
     */
    public int reconcileTombstonedBatch(int batchSize) {
        List<TombstonedWorkflowRunRef> batch = repo.findTombstonedBatch(batchSize);
        int cleaned = 0;
        for (TombstonedWorkflowRunRef ref : batch) {
            try {
                cleanupAndHardDelete(ref.getId(), ref.getExternalRunId());
                cleaned++;
            } catch (Exception e) {
                log.warn(
                        "Reconciler cleanup for tombstoned workflow_run {} failed; will retry next tick: {}",
                        ref.getId(),
                        e.getMessage());
            }
        }
        return cleaned;
    }

    /**
     * afterCommit wrapper that swallows all exceptions so a single run's cleanup failure
     * never takes down the whole cascade. The reconciler retries.
     */
    private void safeCleanup(UUID runId, String externalRunId) {
        try {
            cleanupAndHardDelete(runId, externalRunId);
        } catch (Exception e) {
            log.warn(
                    "afterCommit cleanup for workflow_run {} failed; reconciler will retry: {}", runId, e.getMessage());
        }
    }

    private record SnapshotRef(UUID id, String externalRunId) {}
}
