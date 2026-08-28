package com.choruskube.core.reconciler;

import com.choruskube.core.service.WorkflowRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs concurrently-safe with the afterCommit cleanup in
 * {@code WorkflowRunService.deleteAllByIds}: both paths share
 * {@code WorkflowRunService.cleanupAndHardDelete}, which is idempotent (Temporal terminate
 * swallows {@code WorkflowNotFoundException}; DB DELETE uses a WHERE clause scoped to
 * tombstoned rows). Whichever commits the DELETE first wins.
 *
 * <p>No grace period: cadence is purely a cost/latency knob.
 */
@Component
public class WorkflowRunReconciler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunReconciler.class);

    private final WorkflowRunService workflowRunService;
    private final int batchSize;

    public WorkflowRunReconciler(
            WorkflowRunService workflowRunService,
            @Value("${choruskube.reconciler.workflow-run.batch-size:100}") int batchSize) {
        this.workflowRunService = workflowRunService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${choruskube.reconciler.workflow-run.interval:PT1M}")
    public void reconcile() {
        try {
            int cleaned = workflowRunService.reconcileTombstonedBatch(batchSize);
            if (cleaned > 0) {
                log.info("WorkflowRunReconciler cleaned {} tombstoned row(s)", cleaned);
            }
        } catch (Exception e) {
            log.error("WorkflowRunReconciler tick failed: {}", e.getMessage(), e);
        }
    }
}
