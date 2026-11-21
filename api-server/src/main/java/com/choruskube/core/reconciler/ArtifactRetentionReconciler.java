package com.choruskube.core.reconciler;

import com.choruskube.core.service.ArtifactRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires closed runs' storage trees and sweeps abandoned staged uploads once the retention period
 * a deployment sets has passed. The period is the only switch: while it is unset a pass returns
 * before reading anything.
 */
@Component
public class ArtifactRetentionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRetentionReconciler.class);

    private final ArtifactRetentionService retention;

    public ArtifactRetentionReconciler(ArtifactRetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${choruskube.artifact-retention.interval:PT5M}")
    public void reconcile() {
        try {
            int expired = retention.expireDue();
            if (expired > 0) {
                log.info("ArtifactRetentionReconciler expired the artifacts of {} run(s)", expired);
            }
        } catch (Exception e) {
            log.error("ArtifactRetentionReconciler could not run its run-expiry pass: {}", e.getMessage(), e);
        }
        try {
            int swept = retention.deleteAbandonedStaging();
            if (swept > 0) {
                log.info("ArtifactRetentionReconciler deleted {} abandoned staging object(s)", swept);
            }
        } catch (Exception e) {
            log.error("ArtifactRetentionReconciler could not run its staging sweep: {}", e.getMessage(), e);
        }
    }
}
