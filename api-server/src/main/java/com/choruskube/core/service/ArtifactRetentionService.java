package com.choruskube.core.service;

import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Deletes a closed run's whole storage tree once the retention period a deployment sets has passed
 * since the run closed, then records when on the run. Also deletes staged uploads no run took once
 * they are a day old. Both do nothing while the period is unset. Not transactional: each run's or
 * prefix's deletion stands alone, so one failure never holds back another, and a concurrent pass or
 * replica only repeats work.
 */
@Service
public class ArtifactRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionService.class);

    static final Duration MINIMUM_PERIOD = Duration.ofDays(1);

    // Staging is transient by contract (a run start moves or discards it in the same request), so an
    // object's age here is itself a positive record that it was abandoned.
    static final Duration STAGING_MAX_AGE = Duration.ofDays(1);

    private final WorkflowRunRepository runRepo;
    private final RunStorageScope storageScope;
    private final ObjectTreeDeleter treeDeleter;
    private final RunEventPublisher eventPublisher;
    private final Duration period;
    private final int batchSize;

    public ArtifactRetentionService(
            WorkflowRunRepository runRepo,
            RunStorageScope storageScope,
            ObjectTreeDeleter treeDeleter,
            RunEventPublisher eventPublisher,
            @Value("${objectstore.artifact-retention:}") Duration period,
            @Value("${choruskube.artifact-retention.batch-size:50}") int batchSize) {
        // A bare number binds as milliseconds, and so would delete every closed run's tree on the first pass.
        if (period != null && period.compareTo(MINIMUM_PERIOD) < 0) {
            throw new IllegalStateException("objectstore.artifact-retention must be at least " + MINIMUM_PERIOD
                    + ", got " + period + "; leave it unset to keep every run's artifacts");
        }
        this.runRepo = runRepo;
        this.storageScope = storageScope;
        this.treeDeleter = treeDeleter;
        this.eventPublisher = eventPublisher;
        this.period = period;
        this.batchSize = batchSize;
    }

    /** One pass over the oldest due runs, at most one batch of them. Returns how many it expired. */
    public int expireDue() {
        if (period == null) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(period);
        int expired = 0;
        for (UUID runId :
                runRepo.findExpirable(WorkflowRunStatusGroups.TERMINAL, cutoff, PageRequest.of(0, batchSize))) {
            try {
                if (expire(runId)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire the artifacts of run {}; will be retried: {}", runId, e.getMessage(), e);
            }
        }
        return expired;
    }

    /**
     * Deletes staging objects older than {@link #STAGING_MAX_AGE} under every organization's
     * staging area, while a retention period is set — otherwise returns 0 without touching the
     * store. Each prefix is swept independently: a failure on one is logged and the rest still run.
     * Never touches anything under a run's own tree. Returns how many objects it deleted.
     */
    public int deleteAbandonedStaging() {
        if (period == null) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(STAGING_MAX_AGE);
        int deleted = 0;
        for (String prefix : treeDeleter.storagePrefixes()) {
            try {
                deleted += treeDeleter.deleteModifiedBefore(prefix + UploadService.STAGING_SEGMENT, cutoff);
            } catch (RuntimeException e) {
                log.warn("Could not sweep staged uploads under {}: {}", prefix, e.getMessage(), e);
            }
        }
        return deleted;
    }

    /**
     * Expires {@code runId} as if its retention period had passed, whether or not one is set; every
     * other condition a pass applies still holds. A no-op for a run already expired.
     */
    public void expireNow(UUID runId) {
        WorkflowRun run =
                runRepo.findById(runId).orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));
        if (run.getArtifactsExpiredAt() != null) {
            return;
        }
        if (runRepo.findExpirableById(WorkflowRunStatusGroups.TERMINAL, runId).isEmpty()) {
            throw new ConflictException(
                    "Run " + runId + " is still open, or something still has to read its storage tree");
        }
        if (!expire(runId)
                && runRepo.findById(runId)
                        .map(WorkflowRun::getArtifactsExpiredAt)
                        .isEmpty()) {
            throw new IllegalStateException("Run " + runId + "'s storage tree still lists objects after deletion");
        }
    }

    /** Whether this call recorded the expiry: false when objects remain, or another pass recorded it first. */
    private boolean expire(UUID runId) {
        String tree = storageScope.treeOf(runId);
        treeDeleter.deleteAll(tree);
        // Recorded only once the tree lists empty, so a run never reads as expired while objects remain.
        if (!treeDeleter.isEmpty(tree)) {
            log.warn("Run {}'s storage tree still lists objects after deletion; will be retried", runId);
            return false;
        }
        if (runRepo.markArtifactsExpired(runId, Instant.now()) == 0) {
            return false;
        }
        eventPublisher.publishRunArtifactsExpired(runId);
        log.info("Expired the artifacts of run {}", runId);
        return true;
    }
}
